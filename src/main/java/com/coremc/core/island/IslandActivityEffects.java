package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.enchant.EnchantBlocks;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
import org.bukkit.potion.PotionEffectType;

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

    /** Harvested block -> the crop item crop-yield bonuses. */
    private static final Map<Material, Material> CROP_ITEMS = Map.ofEntries(
            Map.entry(Material.WHEAT, Material.WHEAT),
            Map.entry(Material.CARROTS, Material.CARROT),
            Map.entry(Material.POTATOES, Material.POTATO),
            Map.entry(Material.BEETROOTS, Material.BEETROOT),
            Map.entry(Material.NETHER_WART, Material.NETHER_WART),
            Map.entry(Material.COCOA, Material.COCOA_BEANS),
            Map.entry(Material.MELON, Material.MELON_SLICE),
            Map.entry(Material.PUMPKIN, Material.PUMPKIN),
            Map.entry(Material.SUGAR_CANE, Material.SUGAR_CANE),
            Map.entry(Material.CACTUS, Material.CACTUS));

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

    private int cfgInt(final String track, final String key, final int fallback) {
        return rewards.cfgInt(track, key, fallback);
    }

    private int cfgPercent(final String track, final String key, final int fallback) {
        return rewards.cfgPercent(track, key, fallback);
    }

    private boolean roll(final double chance) {
        return rewards.roll(chance);
    }

    /**
     * Scales an island grant by the player's buff (BUFF stage, applied
     * exactly once per grant; probabilistic rounding keeps fractions fair).
     */
    private int scaleFor(final Player player, final String buffId, final int base) {
        return rewards.scaleFor(player, buffId, base);
    }

    /** Rare/treasure chance with island-luck applied (capped at 95%). */
    private boolean luckRoll(final Player player, final double chance) {
        return rewards.luckRoll(player, chance);
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
        final int fortune = tierOf(island, "farming-fortune");
        if (fortune > 0 && roll(fortune
                * cfgPercent("farming-fortune", "chance-percent-per-level", 10) / 100.0)) {
            bonusCopies(block, scaleFor(player, "farming-boost", 1));
        }
        final int yieldTier = tierOf(island, "crop-yield");
        if (yieldTier > 0) {
            final Material crop = CROP_ITEMS.get(block.getType());
            if (crop != null) {
                block.getWorld().dropItemNaturally(
                        block.getLocation(),
                        new ItemStack(crop, scaleFor(player, "farming-boost", Math.max(1, yieldTier / 2))));
            }
        }
        tryCurrency(player, island, "farming-tokens", Currency.SKY_TOKENS);
        tryCurrency(player, island, "farming-credits", Currency.CREDITS);
        final int speed = hasteAmpForTier(tierOf(island, "harvest-speed"));
        if (speed >= 0) {
            plugin.enchantEngine().applyPotion(player, PotionEffectType.HASTE, speed, 100);
        }
    }

    private void loggingProcs(final Player player, final Island island, final Block block) {
        rewards.loggingProcs(player, island, block);
    }

    /** Drops N extra copies of the block's BASE drops (no tool — no Fortune double-dip). */
    private void bonusCopies(final Block block, final int copies) {
        rewards.bonusCopies(block, copies);
    }

    /** One island token/credit proc: chance roll, then the boosted currency funnel. */
    private void tryCurrency(final Player player, final Island island, final String track,
            final Currency currency) {
        rewards.tryCurrency(player, island, track, currency);
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
        final int tier = tierOf(island.get(), "seed-efficiency");
        if (tier <= 0) {
            return;
        }
        if (roll(tier * cfgPercent("seed-efficiency", "chance-percent-per-level", 15) / 100.0)) {
            final Map<Integer, ItemStack> leftover =
                    event.getPlayer().getInventory().addItem(new ItemStack(seed));
            leftover.values().forEach(rest -> event.getPlayer().getWorld()
                    .dropItemNaturally(event.getPlayer().getLocation(), rest));
        }
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
        if (island.isEmpty()) {
            return;
        }
        final int tier = tierOf(island.get(), "fishing-speed");
        if (tier <= 0) {
            return;
        }
        final double reduction = Math.min(0.9, tier
                * cfgPercent("fishing-speed", "reduction-percent-per-level", 10) / 100.0);
        final var hookEntity = event.getHook();
        final int min = Math.max(20, (int) Math.round(hookEntity.getMinWaitTime() * (1.0 - reduction)));
        final int max = Math.max(min + 20, (int) Math.round(hookEntity.getMaxWaitTime() * (1.0 - reduction)));
        hookEntity.setWaitTime(min, max);
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
        if (island.isEmpty()) {
            return;
        }
        final Island value = island.get();
        final Player player = event.getPlayer();
        final int fortune = tierOf(value, "fishing-fortune");
        if (fortune > 0 && roll(fortune
                * cfgPercent("fishing-fortune", "chance-percent-per-level", 10) / 100.0)) {
            final int copies = scaleFor(player, "fishing-boost", 1);
            for (int copy = 0; copy < copies; copy++) {
                at.getWorld().dropItemNaturally(hooked.getLocation(), hooked.getItemStack().clone());
            }
        }
        final int treasure = tierOf(value, "fishing-treasure");
        if (treasure > 0
                && luckRoll(player, treasure * cfgPercent("fishing-treasure", "chance-percent-per-level", 5) / 100.0)) {
            grantTable(player, "fishing-treasure", "rewards", RoleCategory.FISHING, false);
        }
        final int rare = tierOf(value, "fishing-rare");
        if (rare > 0 && luckRoll(player, rare * cfgPercent("fishing-rare", "chance-percent-per-level", 5) / 100.0)) {
            at.getWorld().dropItemNaturally(hooked.getLocation(), hooked.getItemStack().clone());
            plugin.playerData().profileOf(player.getUniqueId()).ifPresent(profile -> plugin.roles()
                    .awardCategoryXp(player, profile, RoleCategory.FISHING,
                            (long) rare * cfgInt("fishing-rare", "bonus-xp-per-level", 5)));
        }
        tryCurrency(player, value, "fishing-tokens", Currency.SKY_TOKENS);
        tryCurrency(player, value, "fishing-credits", Currency.CREDITS);
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
            spawnerBoostKill(killer, island.get(), event);
            return;
        }
        final int fortune = tierOf(island.get(), "slayer-fortune");
        if (fortune > 0 && roll(fortune
                * cfgPercent("slayer-fortune", "chance-percent-per-level", 10) / 100.0)) {
            // Snapshot first (adding while iterating would corrupt the drop list),
            // then repeat the snapshot for every buff-scaled bonus round.
            final List<ItemStack> base = new ArrayList<>();
            for (final ItemStack stack : event.getDrops()) {
                if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                    base.add(stack.clone());
                }
            }
            final int rounds = scaleFor(killer, "slaying-boost", 1);
            for (int round = 0; round < rounds; round++) {
                for (final ItemStack stack : base) {
                    event.getDrops().add(stack.clone());
                }
            }
        }
        tryCurrency(killer, island.get(), "slaying-tokens", Currency.SKY_TOKENS);
        tryCurrency(killer, island.get(), "slaying-credits", Currency.CREDITS);
        final int rare = tierOf(island.get(), "rare-drops");
        if (rare > 0 && luckRoll(killer, rare * cfgPercent("rare-drops", "chance-percent-per-level", 5) / 100.0)) {
            grantTable(killer, "rare-drops", "rewards", RoleCategory.SLAYING, false);
        }
    }

    /** Spawner-boost kill effects: drop copies, dropped-XP scaling, ITEM/XP rare table. */
    private void spawnerBoostKill(final Player killer, final Island island, final EntityDeathEvent event) {
        final int tier = tierOf(island, "spawner-boost");
        if (tier <= 0) {
            return;
        }
        if (roll(tier * cfgPercent("spawner-boost", "drop-bonus-percent-per-level", 10) / 100.0)) {
            final List<ItemStack> extras = new ArrayList<>();
            for (final ItemStack stack : event.getDrops()) {
                if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                    extras.add(stack.clone());
                }
            }
            event.getDrops().addAll(extras);
        }
        final int dropped = event.getDroppedExp();
        if (dropped > 0) {
            final double mult = 1.0 + tier
                    * cfgPercent("spawner-boost", "xp-percent-per-level", 10) / 100.0;
            event.setDroppedExp(Math.max(dropped, (int) Math.round(dropped * mult)));
        }
        if (luckRoll(killer, tier * cfgPercent("spawner-boost", "rare-percent-per-level", 5) / 100.0)) {
            grantTable(killer, "spawner-boost", "rare-rewards", RoleCategory.SLAYING, true);
        }
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
        final int tier = tierOf(island.get(), "boss-damage");
        if (tier <= 0) {
            return;
        }
        final List<String> bosses =
                plugin.getConfig().getStringList("island.upgrades.boss-damage.boss-types");
        if (!bosses.contains(victim.getType().name())) {
            return;
        }
        final double mult = 1.0 + tier
                * cfgPercent("boss-damage", "damage-percent-per-level", 10) / 100.0;
        event.setDamage(event.getDamage() * mult);
    }

    // ------------------------------------------------------------------ mob rate + mob cap

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        mobSpawns.onCreatureSpawn(event);
    }

    // ------------------------------------------------------------------ island reward tables

    /**
     * Rolls an island reward table once (every entry rolls independently;
     * all hits granted through the standard funnels so enchant boosts
     * stack intentionally). Spawner tables grant ITEM/XP only.
     */
    private void grantTable(final Player player, final String track, final String tableKey,
            final RoleCategory category, final boolean spawnerSafe) {
        rewards.grantTable(player, track, tableKey, category, spawnerSafe);
    }
}
