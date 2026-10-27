package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.placeable.PlaceableService;
import com.coremc.core.spawner.SpawnerMobTagger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Owns the side effects and calculations for island-category upgrade tracks. */
public final class IslandUpgradeEffectCoordinator {

    /** crop -> replant material (itself) + the seed material it consumes. */
    private static final Map<Material, Material> SEEDS = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS);

    private final CoreMCPlugin plugin;
    private final NamespacedKey spawnerBornKey;
    /** Re-entry guard: the mine-all sweep re-fires onBlockBreak per block. */
    private boolean cubeMineAllActive;

    public IslandUpgradeEffectCoordinator(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.spawnerBornKey = new NamespacedKey(plugin, "spawner-born");
    }

    /** Called right after a successful upgrade purchase (same tick). */
    public void onPurchased(final Island island, final String upgradeId, final int newTier) {
        if ("mining-cube".equals(upgradeId)) {
            plugin.miningCube().rebuild(island);
        } else if ("spawner-boost".equals(upgradeId)) {
            retuneIslandSpawners(island);
        }
    }

    /** Spawner delay after a boost: base × (1 - pct×tier/100), floored at 20 ticks. */
    public static int reducedDelayTicks(final int baseTicks, final int tier, final int pctPerLevel) {
        if (tier <= 0 || pctPerLevel <= 0) {
            return baseTicks;
        }
        return Math.max(20, (int) Math.round(baseTicks * (1.0 - pctPerLevel * tier / 100.0)));
    }

    /** Generator cooldown after a boost: base × (1 - pct×tier/100), floored at 1s. */
    public static long reducedCooldownSeconds(final long baseSeconds, final int tier,
            final int pctPerLevel) {
        if (tier <= 0 || pctPerLevel <= 0) {
            return baseSeconds;
        }
        return Math.max(1L, Math.round(baseSeconds * (1.0 - pctPerLevel * tier / 100.0)));
    }

    /** +1 base generator product every N boost tiers (non-positive N disables). */
    public static int bonusBaseProducts(final int tier, final int everyNTiers) {
        if (tier <= 0 || everyNTiers <= 0) {
            return 0;
        }
        return tier / everyNTiers;
    }

    /** Human-readable material name (title-cased) for harvest messages. */
    public static String prettyName(final Material material) {
        final String[] words = material.name().toLowerCase(java.util.Locale.ROOT).split("_");
        final StringBuilder out = new StringBuilder();
        for (final String word : words) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** Tier of any track on the island containing (world,x,z), or 0 in the wild. */
    public int trackTierAt(final String world, final int x, final int z, final String track) {
        return plugin.islands().islandAt(world, x, z)
                .map(island -> island.upgrades().getOrDefault(track, 0))
                .orElse(0);
    }

    public double generatorBonusYieldChance(final int tier) {
        return tier <= 0 ? 0.0
                : tier * configPercent("generator-boost", "bonus-yield-percent-per-level", 10) / 100.0;
    }

    public double generatorRareChance(final int tier) {
        return tier <= 0 ? 0.0
                : tier * configPercent("generator-boost", "rare-percent-per-level", 5) / 100.0;
    }

    /** Weighted roll on the generator rare-products table (empty when unusable). */
    public Optional<Material> rollGeneratorRare() {
        final ConfigurationSection section = plugin.getConfig()
                .getConfigurationSection("island.upgrades.generator-boost.rare-products");
        if (section == null) {
            return Optional.empty();
        }
        final List<Map.Entry<Material, Integer>> entries = new ArrayList<>();
        int total = 0;
        for (final String key : section.getKeys(false)) {
            final int weight = Math.max(0, section.getInt(key, 0));
            final Material material = Material.matchMaterial(key);
            if (weight > 0 && material != null && material.isItem()) {
                entries.add(Map.entry(material, weight));
                total += weight;
            }
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        int pick = ThreadLocalRandom.current().nextInt(total);
        for (final Map.Entry<Material, Integer> entry : entries) {
            pick -= entry.getValue();
            if (pick < 0) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.of(entries.get(entries.size() - 1).getKey());
    }

    /** Re-applies spawner delays to every registered spawner on an island. */
    public void retuneIslandSpawners(final Island island) {
        final World world = plugin.getServer().getWorld(island.worldName());
        if (world == null) {
            return;
        }
        final int tier = island.upgrades().getOrDefault("spawner-boost", 0);
        if (tier <= 0) {
            return;
        }
        final int pct = configPercent("spawner-boost", "delay-reduction-percent-per-level", 10);
        for (final Map.Entry<String, PlaceableService.Placement> entry :
                plugin.placeables().placements().entrySet()) {
            final var placement = entry.getValue();
            if (!placement.worldName().equals(island.worldName())) {
                continue;
            }
            if (placement.type() != PlaceableService.Type.SPAWNER) {
                continue;
            }
            if (!plugin.islands().containsBlock(island, placement.x(), placement.z())) {
                continue;
            }
            final Block block = world.getBlockAt(placement.x(), placement.y(), placement.z());
            if (!(block.getState() instanceof org.bukkit.block.CreatureSpawner spawner)) {
                continue;
            }
            plugin.spawners().tierFor(placement.id()).ifPresent(ref -> {
                int reduced = reducedDelayTicks(ref.tier().spawnDelayTicks(), tier, pct);
                reduced = Math.max(20, (int) Math.round(
                        reduced * plugin.islandBuffs().islandSpawnerDelayMult(island)));
                spawner.setMinSpawnDelay(reduced);
                spawner.setMaxSpawnDelay(reduced);
                spawner.update(true);
            });
        }
    }

    /** Extra-spawn roll for spawner cycles on boosted islands. */
    public void onSpawnerSpawn(final CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        final Location location = event.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        final int tier = trackTierAt(
                location.getWorld().getName(), location.getBlockX(), location.getBlockZ(), "spawner-boost");
        if (tier <= 0) {
            return;
        }
        final double chance = tier * configPercent("spawner-boost", "extra-spawn-percent-per-level", 5) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        // One extra mob of the same kind, tagged with the same identity as the base mob.
        final Entity extra = location.getWorld().spawnEntity(location, event.getEntityType());
        final var tags = plugin.spawners().tags();
        final String tierId = tags.tierIdOf(event.getEntity()).orElse(null);
        final boolean ancient = tags.isAncient(event.getEntity());
        if (tierId != null) {
            tags.tag(extra, tierId, ancient);
        } else {
            extra.getPersistentDataContainer().set(spawnerBornKey, PersistentDataType.BYTE, (byte) 1);
        }
        if (ancient && extra instanceof org.bukkit.entity.LivingEntity living) {
            final var ref = plugin.spawners().tierFor(tierId);
            ref.ifPresent(value -> tags.decorateAncient(plugin, living,
                    SpawnerMobTagger.prettyEntityName(value.mob().entityType().name())));
        }
    }

    /** Applies the island-category break upgrades after team-island admission. */
    public void blockBreak(final BlockBreakEvent event, final Island island) {
        final Block block = event.getBlock();
        tryWoodcutter(event, island, block);
        tryCropRegrowth(event, island, block);
        tryCubeMineAll(event, island, block);
    }

    private void tryCubeMineAll(final BlockBreakEvent event, final Island island, final Block block) {
        if (cubeMineAllActive) {
            return;
        }
        final int tier = island.upgrades().getOrDefault("mining-cube", 0);
        if (tier < plugin.miningCube().mineAllTier()) {
            return;
        }
        if (!plugin.miningCube().isCubeBlock(island, block)) {
            return;
        }
        cubeMineAllActive = true;
        try {
            plugin.miningCube().mineAll(island, event.getPlayer());
        } finally {
            cubeMineAllActive = false;
        }
    }

    private void tryWoodcutter(final BlockBreakEvent event, final Island island, final Block block) {
        if (!org.bukkit.Tag.LOGS.isTagged(block.getType())) {
            return;
        }
        final int tier = island.upgrades().getOrDefault("woodcutter", 0);
        if (tier <= 0) {
            return;
        }
        final double chance = tier * configPercent("woodcutter", "chance-percent-per-level", 10) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(block.getType()));
        }
    }

    private void tryCropRegrowth(final BlockBreakEvent event, final Island island, final Block crop) {
        final Material seed = SEEDS.get(crop.getType());
        if (seed == null || !(crop.getBlockData() instanceof Ageable ageable)
                || ageable.getAge() < ageable.getMaximumAge()) {
            return;
        }
        final int tier = island.upgrades().getOrDefault("crop-regrowth", 0);
        if (tier <= 0) {
            return;
        }
        final double chance = tier * configPercent("crop-regrowth", "chance-percent-per-level", 5) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        final List<ItemStack> drops = new ArrayList<>(crop.getDrops(event.getPlayer()
                .getInventory().getItemInMainHand()));
        boolean consumed = false;
        for (int i = 0; i < drops.size(); i++) {
            if (drops.get(i).getType() == seed) {
                final ItemStack stack = drops.get(i);
                if (stack.getAmount() > 1) {
                    stack.setAmount(stack.getAmount() - 1);
                } else {
                    drops.remove(i);
                }
                consumed = true;
                break;
            }
        }
        if (!consumed) {
            return;
        }
        event.setDropItems(false);
        for (final ItemStack remaining : drops) {
            crop.getWorld().dropItemNaturally(crop.getLocation(), remaining);
        }
        final Material cropType = crop.getType();
        plugin.tasks().run(() -> {
            if (crop.getType().isAir() && crop.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND) {
                crop.setType(cropType);
            }
        });
    }

    public void fisherBlessing(final Player player, final Island island, final Item caught,
            final World waterWorld) {
        final int tier = island.upgrades().getOrDefault("fisher-blessing", 0);
        if (tier <= 0) {
            return;
        }
        final double chance = tier * configPercent("fisher-blessing", "chance-percent-per-level", 10) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            waterWorld.dropItemNaturally(caught.getLocation(), caught.getItemStack().clone());
        }
    }

    public void slayerForce(final Island island, final EntityDamageByEntityEvent event) {
        final int tier = island.upgrades().getOrDefault("slayer-force", 0);
        if (tier <= 0) {
            return;
        }
        final double bonus = tier * configPercent("slayer-force", "damage-percent-per-level", 5) / 100.0;
        event.setDamage(event.getDamage() * (1.0 + bonus));
    }

    private int configPercent(final String trackId, final String key, final int fallback) {
        return Math.max(0, plugin.getConfig().getInt("island.upgrades." + trackId + "." + key, fallback));
    }
}
