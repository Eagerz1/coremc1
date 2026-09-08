package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.scheduler.TaskService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The Mining Cube upgrade effect.
 *
 * Every island can buy a mining cube: a (tier+1)^3 block of stone and
 * ores standing east of the platform (tier 4 = the spec's 5x5 mining
 * area). Blocks mined out of the cube regenerate one at a time on a
 * timer, so a team always has a reliable source of stone and ores —
 * the CoreMC replacement for the classic cobblestone generator.
 *
 * Content weights and the regeneration cadence live entirely in
 * config.yml ({@code island.upgrades.mining-cube}).
 */
public final class MiningCubeService {

    /** Empty space kept between the island platform and the cube. */
    private static final int PLATFORM_RADIUS = 3;
    private static final int GAP = 2;

    private final CoreMCPlugin plugin;
    private final List<Map.Entry<Material, Integer>> weights = new ArrayList<>();
    private int totalWeight = 0;
    private int regenSeconds = 30;

    public MiningCubeService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)reads content weights from config; called at (re)load. */
    public void load() {
        weights.clear();
        totalWeight = 0;
        final ConfigurationSection section =
                plugin.getConfig().getConfigurationSection("island.upgrades.mining-cube.contents");
        if (section != null) {
            for (final String id : section.getKeys(false)) {
                final Material material = Material.matchMaterial(id);
                final int weight = Math.max(0, section.getInt(id));
                if (material != null && weight > 0) {
                    weights.add(Map.entry(material, weight));
                    totalWeight += weight;
                }
            }
        }
        if (weights.isEmpty()) {
            plugin.getLogger().warning("mining-cube.contents empty — defaulting to STONE.");
            weights.add(Map.entry(Material.STONE, 1));
            totalWeight = 1;
        }
        this.regenSeconds = Math.max(5, plugin.getConfig()
                .getInt("island.upgrades.mining-cube.regen-seconds", 30));
    }

    /** Cube edge length for a purchased tier (tier 4 -> 5x5 area). */
    public int sizeFor(final int tier) {
        return Math.max(0, Math.min(tier, plugin.coreConfig().upgradeMaxTier("mining-cube")) + 1);
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
                    world.getBlockAt(bx, by, bz).setType(rollMaterial());
                }
            }
        }
    }

    /**
     * Starts the regeneration timer: every {@code regen-seconds} each
     * island with a Mining Cube gets ONE randomly-missing block back.
     * Scheduled through TaskService so shutdown is clean; work per tick
     * is (islands * cube volume) light.
     */
    public void startRegeneration(final TaskService tasks) {
        tasks.runTimer(this::regenerateMissingBlocks, 20L * regenSeconds, 20L * regenSeconds);
    }

    void regenerateMissingBlocks() {
        for (final Island island : plugin.islands().allIslands()) {
            final int tier = island.upgrades().getOrDefault("mining-cube", 0);
            if (tier <= 0) {
                continue;
            }
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
            final int[] cell = missing.get(ThreadLocalRandom.current().nextInt(missing.size()));
            world.getBlockAt(cell[0], cell[1], cell[2]).setType(rollMaterial());
        }
    }

    private Material rollMaterial() {
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        for (final Map.Entry<Material, Integer> entry : weights) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        return Material.STONE;
    }
}
