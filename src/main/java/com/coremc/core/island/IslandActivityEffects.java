package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.enchant.EnchantBlocks;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Live effects of the per-activity island upgrade tracks. Every effect
 * is team-and-island scoped: the action must happen inside a border
 * the actor's team owns. All chances/amounts come from
 * {@code island.upgrades.<track>} (code fallbacks mirror the defaults).
 *
 * Stacking (intentional, never accidental — the pipeline is BASE →
 * UPGRADE → BUFF → ROLE → ENCHANT → TEMP):
 *  - island currency grants ride the enchant currency funnel, so role
 *    MULTIPLIERs and token/credit buffs apply on top — enchant ×
 *    island × buff, one code path;
 *  - island reward tables grant through the same funnels (currency,
 *    XP, souls, keys); ITEM amounts scale with the activity buff here;
 *  - island XP tracks multiply inside the single XP funnel
 *    (RoleService), after enchant/combo boosts, beside the xp-boost;
 *  - rare/treasure chances multiply with island-luck here (capped).
 *
 * Anti-abuse: spawner-born mobs are excluded from the wild-kill
 * tracks (slayer-fortune, slaying-tokens/credits, rare-drops) and get
 * only the spawner-boost track's ITEM/XP effects — spawner farms can
 * never print economy. Bonus drops copy BASE block drops (no tool),
 * so tool Fortune never double-dips.
 */
public final class IslandActivityEffects implements Listener {

    /** Items whose placement seed-efficiency can refund. */
    private static final Set<Material> SEED_ITEMS = EnumSet.of(
            Material.WHEAT_SEEDS,
            Material.CARROT,
            Material.POTATO,
            Material.BEETROOT_SEEDS,
            Material.NETHER_WART,
            Material.COCOA_BEANS,
            Material.MELON_SEEDS,
            Material.PUMPKIN_SEEDS,
            Material.SUGAR_CANE,
            Material.CACTUS);

    private final CoreMCPlugin plugin;
    /** Same spawner-born tag the slayer pipeline filters on. */
    private final NamespacedKey spawnerBornKey;
    private final IslandMobSpawnCoordinator mobSpawns;
    private final IslandActivityRewardCoordinator rewards;

    public IslandActivityEffects(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.spawnerBornKey = new NamespacedKey(plugin, "spawner-born");
        this.mobSpawns = new IslandMobSpawnCoordinator(plugin);
        this.rewards = new IslandActivityRewardCoordinator(plugin);
    }

    /** Clears parsed reward-table caches (called on /coremc reload). */
    public void clearCaches() {
        rewards.clearCache();
    }

    /** Drops the mob-cap counter of a deleted island (no leaks). */
    public void purgeIsland(final UUID islandId) {
        mobSpawns.purgeIsland(islandId);
    }

    // ------------------------------------------------------------------ helpers

    private Optional<Island> teamIslandAt(
            final String world, final int x, final int z, final UUID player) {
        return plugin.islands().islandAt(world, x, z)
                .filter(island -> island.roleOf(player) != null);
    }

    private int tierOf(final Island island, final String track) {
        return rewards.tierOf(island, track);
    }

    private int cfgPercent(final String track, final String key, final int fallback) {
        return rewards.cfgPercent(track, key, fallback);
    }

    private boolean roll(final double chance) {
        return rewards.roll(chance);
    }

    /**
     * Haste amplifier for a speed track: -1 (no effect) at tier 0,
     * 0 (Haste I) at 1-2, 1 (Haste II) at 3-4, 2 (Haste III) at 5+.
     */
    public static int hasteAmpForTier(final int tier) {
        if (tier <= 0) {
            return -1;
        }
        if (tier >= 5) {
            return 2;
        }
        if (tier >= 3) {
            return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ mining / logging / farming

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        if (plugin.placeables().at(block.getLocation()).isPresent()) {
            return; // breaking cores is the placeable system's business, not mining
        }
        final var island = teamIslandAt(block.getWorld().getName(), block.getX(), block.getZ(),
                event.getPlayer().getUniqueId());
        if (island.isEmpty()) {
            return;
        }
        final Material type = block.getType();
        if (EnchantBlocks.isLog(type)) {
            loggingProcs(event.getPlayer(), island.get(), block);
        } else if (EnchantBlocks.isFarmBlock(type) && EnchantBlocks.isMatureHarvest(block)) {
            farmingProcs(event.getPlayer(), island.get(), block);
        } else if (!EnchantBlocks.isWoodLike(type) && !EnchantBlocks.isFarmBlock(type)) {
            miningProcs(event.getPlayer(), island.get(), block);
        }
    }

    private void miningProcs(final Player player, final Island island, final Block block) {
        rewards.miningProcs(player, island, block);
    }

    private void farmingProcs(final Player player, final Island island, final Block block) {
        rewards.farmingProcs(player, island, block);
    }

    private void loggingProcs(final Player player, final Island island, final Block block) {
        rewards.loggingProcs(player, island, block);
    }

    // ------------------------------------------------------------------ seed efficiency

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSeedPlace(final BlockPlaceEvent event) {
        final Material seed = event.getItemInHand().getType();
        if (!SEED_ITEMS.contains(seed)) {
            return;
        }
        final Block block = event.getBlockPlaced();
        final var island = teamIslandAt(block.getWorld().getName(), block.getX(), block.getZ(),
                event.getPlayer().getUniqueId());
        if (island.isEmpty()) {
            return;
        }
        rewards.seedEfficiency(event.getPlayer(), island.get(), seed);
    }

    // ------------------------------------------------------------------ fishing

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        switch (event.getState()) {
            case FISHING -> fishingSpeed(event);
            case CAUGHT_FISH -> onCatch(event);
            default -> {
            }
        }
    }

    /** Shrinks the hook's wait window while fishing on the team island. */
    private void fishingSpeed(final PlayerFishEvent event) {
        final Location hook = event.getHook().getLocation();
        if (hook.getWorld() == null) {
            return;
        }
        final var island = teamIslandAt(hook.getWorld().getName(), hook.getBlockX(), hook.getBlockZ(),
                event.getPlayer().getUniqueId());
        island.ifPresent(value -> rewards.fishingSpeed(value, event.getHook()));
    }

    private void onCatch(final PlayerFishEvent event) {
        if (!(event.getCaught() instanceof Item hooked)) {
            return;
        }
        final Location at = event.getHook().getLocation();
        if (at.getWorld() == null) {
            return;
        }
        final var island = teamIslandAt(at.getWorld().getName(), at.getBlockX(), at.getBlockZ(),
                event.getPlayer().getUniqueId());
        island.ifPresent(value -> rewards.fishingCatch(event.getPlayer(), value, hooked, at));
    }

    // ------------------------------------------------------------------ kills (wild + spawner-born)

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity victim = event.getEntity();
        if (victim instanceof Player) {
            return;
        }
        // Mob-cap bookkeeping counts every island mob death (any killer, any team).
        mobSpawns.recordDeath(victim);
        final Location at = victim.getLocation();
        final Player killer = victim.getKiller();
        if (killer == null || at.getWorld() == null) {
            return;
        }
        final var island = teamIslandAt(at.getWorld().getName(), at.getBlockX(), at.getBlockZ(),
                killer.getUniqueId());
        if (island.isEmpty()) {
            return;
        }
        if (isSpawnerBorn(victim)) {
            rewards.spawnerBoostKill(killer, island.get(), event);
            return;
        }
        rewards.slayingKill(killer, island.get(), event);
    }

    private boolean isSpawnerBorn(final LivingEntity victim) {
        return victim.getPersistentDataContainer().has(spawnerBornKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ boss damage

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossDamage(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        final Location at = victim.getLocation();
        if (at.getWorld() == null) {
            return;
        }
        final var island = teamIslandAt(at.getWorld().getName(), at.getBlockX(), at.getBlockZ(),
                player.getUniqueId());
        if (island.isEmpty()) {
            return;
        }
        rewards.bossDamage(player, island.get(), event);
    }

    // ------------------------------------------------------------------ mob rate + mob cap

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        mobSpawns.onCreatureSpawn(event);
    }

}
