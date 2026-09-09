package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Live effects of the non-size upgrade tracks. Role tracks are
 * island-team scoped and config-balanced:
 *
 *  - Farming / Crop Regrowth: harvesting a fully-grown crop on your
 *    island rolls L*5% — on success the crop replants itself and one
 *    seed is consumed from the item drops (no duplication possible),
 *  - Logging / Woodcutter: chopping a log rolls L*10% for a second log,
 *  - Fishing / Fisher's Blessing: a caught fish rolls L*10% for a bonus,
 *  - Slaying / Slayer Force: melee hits on your island deal +L*5%,
 *  - Island / Generator Boost: generator harvest cooldowns on the
 *    island shrink by L*8% (see PlaceableListener),
 *  - Island / Spawner Boost: spawner delays on the island shrink by
 *    L*10% (place-time + purchase retune) and spawner cycles roll
 *    L*5% for one extra mob.
 *
 * Also hosts the purchase hook called from
 * {@link IslandService#purchaseUpgrade} so side-effecting tracks
 * (Mining Cube rebuild, spawner retune) act the moment they are bought.
 */
public final class IslandUpgradeEffects implements Listener {

    private final CoreMCPlugin plugin;
    /**
     * Spawner-born marker — the SAME tag the slayer pipeline filters
     * on (equal NamespacedKey: same plugin namespace + same key), so
     * boost-spawned extras stay ineligible for kill-economy effects.
     */
    private final NamespacedKey spawnerBornKey;

    /** crop -> replant material (itself) + the seed material it consumes. */
    private static final Map<Material, Material> SEEDS = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS);

    public IslandUpgradeEffects(final CoreMCPlugin plugin) {
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

    // ------------------------------------------------------------------ boost math (pure)

    /** Spawner delay after a boost: base × (1 - pct×tier/100), floored at 20 ticks (1s). */
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

    // ------------------------------------------------------------------ boost lookups

    /** Spawner-boost tier of the island containing (world,x,z), or 0 in the wild. */
    public int spawnerBoostTierAt(final String world, final int x, final int z) {
        return boostTierAt(world, x, z, "spawner-boost");
    }

    /** Generator-boost tier of the island containing (world,x,z), or 0 in the wild. */
    public int generatorBoostTierAt(final String world, final int x, final int z) {
        return boostTierAt(world, x, z, "generator-boost");
    }

    private int boostTierAt(final String world, final int x, final int z, final String track) {
        return plugin.islands().islandAt(world, x, z)
                .map(island -> island.upgrades().getOrDefault(track, 0))
                .orElse(0);
    }

    // ------------------------------------------------------------------ spawner boost

    /**
     * Re-applies spawner delays to every spawner placement on the
     * island (purchase hook; place-time application lives in
     * PlaceableListener).
     */
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
        for (final Map.Entry<String, com.coremc.core.placeable.PlaceableService.Placement> entry :
                plugin.placeables().placements().entrySet()) {
            final String key = entry.getKey();
            final int split = key.indexOf(':');
            if (split < 0 || !key.substring(0, split).equals(island.worldName())) {
                continue;
            }
            final var placement = entry.getValue();
            if (placement.type() != com.coremc.core.placeable.PlaceableService.Type.SPAWNER) {
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
                final int reduced = reducedDelayTicks(ref.tier().spawnDelayTicks(), tier, pct);
                spawner.setMinSpawnDelay(reduced);
                spawner.setMaxSpawnDelay(reduced);
                spawner.update(true);
            });
        }
    }

    /** Extra-spawn roll for spawner cycles on boosted islands. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(final CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        final Location location = event.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        final int tier = spawnerBoostTierAt(
                location.getWorld().getName(), location.getBlockX(), location.getBlockZ());
        if (tier <= 0) {
            return;
        }
        final double chance =
                tier * configPercent("spawner-boost", "extra-spawn-percent-per-level", 5) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        // One extra mob of the same kind. Custom-reason spawn (never
        // re-enters this handler) and tagged spawner-born so the slayer
        // economy filter keeps excluding it.
        final Entity extra = location.getWorld().spawnEntity(location, event.getEntityType());
        extra.getPersistentDataContainer().set(spawnerBornKey, PersistentDataType.BYTE, (byte) 1);
    }

    // ------------------------------------------------------------------ crop regrowth + woodcutter

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        final var island = plugin.islands()
                .islandAt(block.getWorld().getName(), block.getX(), block.getZ());
        if (island.isEmpty() || island.get().roleOf(event.getPlayer().getUniqueId()) == null) {
            return;
        }
        final Island value = island.get();
        tryWoodcutter(event, value, block);
        tryCropRegrowth(event, value, block);
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
            return; // not a mature, regrowable crop
        }
        final int tier = island.upgrades().getOrDefault("crop-regrowth", 0);
        if (tier <= 0) {
            return;
        }
        final double chance = tier * configPercent("crop-regrowth", "chance-percent-per-level", 5) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        // One seed is consumed: replay the drops ourselves minus one seed.
        final List<ItemStack> drops = new java.util.ArrayList<>(crop.getDrops(event.getPlayer()
                .getInventory()
                .getItemInMainHand()));
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
            return; // no seed in the harvest — nothing to replant with
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

    // ------------------------------------------------------------------ fisher's blessing

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item caught)) {
            return;
        }
        final Block water = event.getHook().getLocation().getBlock();
        final var island = plugin.islands()
                .islandAt(water.getWorld().getName(), water.getX(), water.getZ());
        if (island.isEmpty() || island.get().roleOf(event.getPlayer().getUniqueId()) == null) {
            return;
        }
        final int tier = island.get().upgrades().getOrDefault("fisher-blessing", 0);
        if (tier <= 0) {
            return;
        }
        final double chance = tier * configPercent("fisher-blessing", "chance-percent-per-level", 10) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            water.getWorld().dropItemNaturally(caught.getLocation(), caught.getItemStack().clone());
        }
    }

    // ------------------------------------------------------------------ slayer force

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeleeDamage(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        final Entity victim = event.getEntity();
        final var island = plugin.islands()
                .islandAt(victim.getWorld().getName(), victim.getLocation().getBlockX(), victim.getLocation().getBlockZ());
        if (island.isEmpty() || island.get().roleOf(player.getUniqueId()) == null) {
            return;
        }
        final int tier = island.get().upgrades().getOrDefault("slayer-force", 0);
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
