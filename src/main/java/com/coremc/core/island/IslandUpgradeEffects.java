package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.block.Block;
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

/**
 * Live effects of the island-category upgrade tracks plus the four
 * original role tracks. Per-activity tracks (fortune/XP/economy/
 * speed/rare/mob-cap/...) live in {@link IslandActivityEffects};
 * island XP/level progression lives in {@link IslandProgressService}.
 *
 *  - Farming / Crop Regrowth: harvesting a fully-grown crop on your
 *    island rolls L*5% — on success the crop replants itself and one
 *    seed is consumed from the item drops (no duplication possible),
 *  - Logging / Woodcutter: chopping a log rolls L*10% for a second log,
 *  - Fishing / Fisher's Blessing: a caught fish rolls L*10% for a bonus,
 *  - Slaying / Slayer Force: melee hits on your island deal +L*5%,
 *  - Island / Generator Boost: generator harvest cooldowns on the
 *    island shrink by L*8%, harvests roll L*10% for +1 product, gain
 *    +1 base product every 2 tiers, and roll L*5% on the rare table
 *    (see PlaceableListener),
 *  - Island / Spawner Boost: spawner delays on the island shrink by
 *    L*10% (place-time + purchase retune), spawner cycles roll L*5%
 *    for one extra mob, and spawner-mob kills gain drop/XP/rare
 *    bonuses (see IslandActivityEffects).
 *
 * Also hosts the purchase hook called from
 * {@link IslandService#purchaseUpgrade} so side-effecting tracks
 * (Mining Cube rebuild, spawner retune) act the moment they are bought.
 */
public final class IslandUpgradeEffects implements Listener {

    private final CoreMCPlugin plugin;
    private final IslandUpgradeEffectCoordinator effects;

    public IslandUpgradeEffects(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.effects = new IslandUpgradeEffectCoordinator(plugin);
    }

    /** Called right after a successful upgrade purchase (same tick). */
    public void onPurchased(final Island island, final String upgradeId, final int newTier) {
        effects.onPurchased(island, upgradeId, newTier);
    }

    // ------------------------------------------------------------------ boost math (pure)

    /** Spawner delay after a boost: base × (1 - pct×tier/100), floored at 20 ticks (1s). */
    public static int reducedDelayTicks(final int baseTicks, final int tier, final int pctPerLevel) {
        return IslandUpgradeEffectCoordinator.reducedDelayTicks(baseTicks, tier, pctPerLevel);
    }

    /** Generator cooldown after a boost: base × (1 - pct×tier/100), floored at 1s. */
    public static long reducedCooldownSeconds(final long baseSeconds, final int tier,
            final int pctPerLevel) {
        return IslandUpgradeEffectCoordinator.reducedCooldownSeconds(baseSeconds, tier, pctPerLevel);
    }

    /** +1 base generator product every N boost tiers (non-positive N disables). Pure. */
    public static int bonusBaseProducts(final int tier, final int everyNTiers) {
        return IslandUpgradeEffectCoordinator.bonusBaseProducts(tier, everyNTiers);
    }

    /** Human-readable material name (title-cased) for harvest messages. Pure. */
    public static String prettyName(final Material material) {
        return IslandUpgradeEffectCoordinator.prettyName(material);
    }

    // ------------------------------------------------------------------ boost lookups

    /** Spawner-boost tier of the island containing (world,x,z), or 0 in the wild. */
    public int spawnerBoostTierAt(final String world, final int x, final int z) {
        return trackTierAt(world, x, z, "spawner-boost");
    }

    /** Generator-boost tier of the island containing (world,x,z), or 0 in the wild. */
    public int generatorBoostTierAt(final String world, final int x, final int z) {
        return trackTierAt(world, x, z, "generator-boost");
    }

    /** Tier of any track on the island containing (world,x,z), or 0 in the wild. */
    public int trackTierAt(final String world, final int x, final int z, final String track) {
        return effects.trackTierAt(world, x, z, track);
    }

    // ------------------------------------------------------------------ generator boost

    /** Bonus-yield (+1 product) chance for a generator-boost tier. */
    public double generatorBonusYieldChance(final int tier) {
        return effects.generatorBonusYieldChance(tier);
    }

    /** Rare-product chance for a generator-boost tier. */
    public double generatorRareChance(final int tier) {
        return effects.generatorRareChance(tier);
    }

    /** Weighted roll on the generator rare-products table (empty when unusable). */
    public Optional<Material> rollGeneratorRare() {
        return effects.rollGeneratorRare();
    }

    // ------------------------------------------------------------------ spawner boost

    /**
     * Re-applies spawner delays to every spawner placement on the
     * island (purchase hook; place-time application lives in
     * PlaceableListener).
     */
    public void retuneIslandSpawners(final Island island) {
        effects.retuneIslandSpawners(island);
    }

    /** Extra-spawn roll for spawner cycles on boosted islands. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(final CreatureSpawnEvent event) {
        effects.onSpawnerSpawn(event);
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
        effects.blockBreak(event, island.get());
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
        effects.fisherBlessing(event.getPlayer(), island.get(), caught, water.getWorld());
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
        effects.slayerForce(island.get(), event);
    }
}
