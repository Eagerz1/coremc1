package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.scheduler.TaskService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The Mining Cube upgrade effect (tiers 1–15).
 *
 * Every island can buy a mining cube: a stone-and-ore block standing
 * east of the platform. The edge grows with the tier but caps at 5
 * (tier 4 = the spec's 5x5 mining area, tiers 5+ keep that size);
 * higher tiers instead regenerate faster, restore more blocks per
 * cycle, and unlock rich contents (emerald/diamond at 8, ancient
 * debris at 12). At mine-all-tier+ (default 5) breaking one cube
 * block mines the entire cube with the held tool.
 *
 * Existing tiers 0–4 keep their exact meaning on the 0–15 scale
 * (size identity), so no migration is needed.
 *
 * Content weights, tier gates and the regeneration cadence live
 * entirely in config.yml ({@code island.upgrades.mining-cube}).
 */
public final class MiningCubeService {

    /** Empty space kept between the island platform and the cube. */
    private static final int PLATFORM_RADIUS = 3;
    private static final int GAP = 2;
    /** Global regen heartbeat; per-island cadence gates on top of it. */
    private static final long REGEN_TICK_SECONDS = 5L;

    private final CoreMCPlugin plugin;
    private final List<Map.Entry<Material, Integer>> baseWeights = new ArrayList<>();
    private int baseTotalWeight = 0;
    private final List<TierContents> gatedContents = new ArrayList<>();
    private int regenBaseSeconds = 30;
    private int regenStepSeconds = 2;
    private int regenMinSeconds = 5;
    private int mineAllTier = 5;
    /** island id -> last regen millis (pruned against the live registry every tick). */
    private final Map<UUID, Long> lastRegen = new ConcurrentHashMap<>();

    private record TierContents(int minTier, List<Map.Entry<Material, Integer>> entries, int total) {
    }

    public MiningCubeService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** Cube edge length for a tier: 0 = none, then tier+1 capped at 5. */
    public static int sizeForTier(final int tier) {
        return tier <= 0 ? 0 : Math.min(tier + 1, 5);
    }

    /** Regen cadence for a tier: max(min, base - (tier-1) * step). */
    public static int regenSecondsFor(final int tier, final int base, final int step, final int min) {
        return Math.max(min, base - (tier - 1) * step);
    }

    /** Blocks restored per regen cycle: 1, +1 at tier 6, +1 at tier 11. */
    public static int blocksPerTick(final int tier) {
        return 1 + (tier >= 6 ? 1 : 0) + (tier >= 11 ? 1 : 0);
    }

    /** (Re)reads weights, tier gates and cadence from config; called at (re)load. */
    public void load() {
        baseWeights.clear();
        baseTotalWeight = 0;
        gatedContents.clear();
        final ConfigurationSection root =
                plugin.getConfig().getConfigurationSection("island.upgrades.mining-cube");
        final ConfigurationSection base =
                plugin.getConfig().getConfigurationSection("island.upgrades.mining-cube.contents");
        if (base != null) {
            for (final String id : base.getKeys(false)) {
                addWeight(baseWeights, id, Math.max(0, base.getInt(id)));
            }
        }
        if (root != null) {
            for (final String key : root.getKeys(false)) {
                if (!key.startsWith("contents-tier-")) {
                    continue;
                }
                final int minTier;
                try {
                    minTier = Integer.parseInt(key.substring("contents-tier-".length()));
                } catch (final NumberFormatException bad) {
                    plugin.getLogger().warning("mining-cube." + key + " has no numeric tier — skipped.");
                    continue;
                }
                final ConfigurationSection gated = root.getConfigurationSection(key);
                if (gated == null) {
                    continue;
                }
                final List<Map.Entry<Material, Integer>> entries = new ArrayList<>();
                for (final String id : gated.getKeys(false)) {
                    addWeight(entries, id, Math.max(0, gated.getInt(id)));
                }
                int total = 0;
                for (final Map.Entry<Material, Integer> entry : entries) {
                    total += entry.getValue();
                }
                if (!entries.isEmpty()) {
                    gatedContents.add(new TierContents(minTier, entries, total));
                }
            }
        }
        for (final Map.Entry<Material, Integer> entry : baseWeights) {
            baseTotalWeight += entry.getValue();
        }
        if (baseTotalWeight <= 0) {
            plugin.getLogger().warning("mining-cube.contents empty — defaulting to STONE.");
            baseWeights.add(Map.entry(Material.STONE, 1));
            baseTotalWeight = 1;
        }
        final int legacy = plugin.getConfig().getInt("island.upgrades.mining-cube.regen-seconds", 30);
        this.regenBaseSeconds = Math.max(5, plugin.getConfig()
                .getInt("island.upgrades.mining-cube.regen-base-seconds", legacy));
        this.regenStepSeconds = Math.max(0, plugin.getConfig()
                .getInt("island.upgrades.mining-cube.regen-step-seconds", 2));
        this.regenMinSeconds = Math.max((int) REGEN_TICK_SECONDS, plugin.getConfig()
                .getInt("island.upgrades.mining-cube.regen-min-seconds", 5));
        this.mineAllTier = Math.max(1, plugin.getConfig()
                .getInt("island.upgrades.mining-cube.mine-all-tier", 5));
    }

    private void addWeight(final List<Map.Entry<Material, Integer>> target, final String id,
            final int weight) {
        final Material material = Material.matchMaterial(id);
        if (material != null && weight > 0) {
            target.add(Map.entry(material, weight));
        }
    }

    /** Cube edge length for a purchased tier (clamped to the configured max). */
    public int sizeFor(final int tier) {
        return sizeForTier(Math.max(0, Math.min(tier, plugin.coreConfig().upgradeMaxTier("mining-cube"))));
    }

    /** Tier at/above which breaking one cube block mines the whole cube. */
    public int mineAllTier() {
        return mineAllTier;
    }

    /** True when the block sits inside the island's current cube volume. */
    public boolean isCubeBlock(final Island island, final Block block) {
        if (block.getWorld() == null || !block.getWorld().getName().equals(island.worldName())) {
            return false;
        }
        final int size = sizeFor(island.upgrades().getOrDefault("mining-cube", 0));
        if (size <= 0) {
            return false;
        }
        final int x0 = island.centerX() + PLATFORM_RADIUS + GAP;
        final int y0 = island.centerY() + 1;
        final int z0 = island.centerZ() - (size / 2);
        final int x = block.getX();
        final int y = block.getY();
        final int z = block.getZ();
        return x >= x0 && x < x0 + size && y >= y0 && y < y0 + size && z >= z0 && z < z0 + size;
    }

    /** Fills (or enlarges) the island's cube; no-op when the tier is 0 or the world is missing. */
    public void rebuild(final Island island) {
        final int tier = island.upgrades().getOrDefault("mining-cube", 0);
        if (tier <= 0) {
            return;
        }
        final World world = Bukkit.getWorld(island.worldName());
        if (world == null) {
            return;
        }
        final int size = sizeFor(tier);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    final int bx = island.centerX() + PLATFORM_RADIUS + GAP + x;
                    final int by = island.centerY() + 1 + y;
                    final int bz = island.centerZ() - (size / 2) + z;
                    world.getBlockAt(bx, by, bz).setType(rollMaterial(tier));
                }
            }
        }
    }

    /**
     * Breaks every remaining cube block with the player's held tool, so
     * fortune applies per block and each nested break pays its XP and
     * procs. The caller guards re-entry (breaks re-fire listeners).
     */
    public void mineAll(final Island island, final Player player) {
        final int size = sizeFor(island.upgrades().getOrDefault("mining-cube", 0));
        if (size <= 0) {
            return;
        }
        final World world = Bukkit.getWorld(island.worldName());
        if (world == null) {
            return;
        }
        final ItemStack held = player.getInventory().getItemInMainHand();
        final int x0 = island.centerX() + PLATFORM_RADIUS + GAP;
        final int y0 = island.centerY() + 1;
        final int z0 = island.centerZ() - (size / 2);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    final Block cell = world.getBlockAt(x0 + x, y0 + y, z0 + z);
                    if (!cell.getType().isAir()) {
                        cell.breakNaturally(held);
                    }
                }
            }
        }
    }

    /**
     * Starts the regeneration heartbeat (every 5s): each island with
     * a Mining Cube regenerates up to its per-tier block allowance
     * once its per-tier cadence has elapsed. Scheduled through
     * TaskService so shutdown is clean.
     */
    public void startRegeneration(final TaskService tasks) {
        tasks.runTimer(this::regenerateMissingBlocks, 20L * REGEN_TICK_SECONDS, 20L * REGEN_TICK_SECONDS);
    }

    void regenerateMissingBlocks() {
        final long now = System.currentTimeMillis();
        final List<Island> islands = new ArrayList<>(plugin.islands().allIslands());
        final Set<UUID> live = new HashSet<>();
        for (final Island island : islands) {
            live.add(island.islandId());
        }
        lastRegen.keySet().retainAll(live); // prune deleted islands
        for (final Island island : islands) {
            final int tier = island.upgrades().getOrDefault("mining-cube", 0);
            if (tier <= 0) {
                lastRegen.remove(island.islandId());
                continue;
            }
            final long cadenceMillis = regenSecondsFor(
                    tier, regenBaseSeconds, regenStepSeconds, regenMinSeconds) * 1000L;
            if (now - lastRegen.getOrDefault(island.islandId(), 0L) < cadenceMillis) {
                continue;
            }
            lastRegen.put(island.islandId(), now);
            final World world = Bukkit.getWorld(island.worldName());
            if (world == null) {
                continue;
            }
            final int size = sizeFor(tier);
            final List<int[]> missing = new ArrayList<>();
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) {
                        final int bx = island.centerX() + PLATFORM_RADIUS + GAP + x;
                        final int by = island.centerY() + 1 + y;
                        final int bz = island.centerZ() - (size / 2) + z;
                        if (world.getBlockAt(bx, by, bz).getType().isAir()) {
                            missing.add(new int[] {bx, by, bz});
                        }
                    }
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            final ThreadLocalRandom random = ThreadLocalRandom.current();
            final int restores = Math.min(blocksPerTick(tier), missing.size());
            for (int pick = 0; pick < restores; pick++) {
                final int[] cell = missing.remove(random.nextInt(missing.size()));
                world.getBlockAt(cell[0], cell[1], cell[2]).setType(rollMaterial(tier));
            }
        }
    }

    /** Weighted roll over the base contents plus every tier gate the tier unlocks. */
    private Material rollMaterial(final int tier) {
        int total = baseTotalWeight;
        for (final TierContents gated : gatedContents) {
            if (tier >= gated.minTier()) {
                total += gated.total();
            }
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (final Map.Entry<Material, Integer> entry : baseWeights) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        for (final TierContents gated : gatedContents) {
            if (tier < gated.minTier()) {
                continue;
            }
            for (final Map.Entry<Material, Integer> entry : gated.entries()) {
                roll -= entry.getValue();
                if (roll < 0) {
                    return entry.getKey();
                }
            }
        }
        return Material.STONE;
    }
}
