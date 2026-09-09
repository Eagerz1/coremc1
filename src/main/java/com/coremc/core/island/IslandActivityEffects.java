package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.enchant.EnchantBlocks;
import com.coremc.core.enchant.RewardRoll;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
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
    /** Parsed island reward tables, cleared on /coremc reload. */
    private final Map<String, List<RewardRoll>> tableCache = new ConcurrentHashMap<>();
    /** islandId -> live natural-mob estimate for the mob-cap track. */
    private final Map<UUID, Integer> mobCounters = new ConcurrentHashMap<>();

    public IslandActivityEffects(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.spawnerBornKey = new NamespacedKey(plugin, "spawner-born");
    }

    /** Clears parsed reward-table caches (called on /coremc reload). */
    public void clearCaches() {
        tableCache.clear();
    }

    /** Drops the mob-cap counter of a deleted island (no leaks). */
    public void purgeIsland(final UUID islandId) {
        mobCounters.remove(islandId);
    }

    // ------------------------------------------------------------------ helpers

    private Optional<Island> teamIslandAt(
            final String world, final int x, final int z, final UUID player) {
        return plugin.islands().islandAt(world, x, z)
                .filter(island -> island.roleOf(player) != null);
    }

    private static int tierOf(final Island island, final String track) {
        return island.upgrades().getOrDefault(track, 0);
    }

    private int cfgInt(final String track, final String key, final int fallback) {
        return plugin.getConfig().getInt("island.upgrades." + track + "." + key, fallback);
    }

    private int cfgPercent(final String track, final String key, final int fallback) {
        return Math.max(0, cfgInt(track, key, fallback));
    }

    private static boolean roll(final double chance) {
        return chance > 0.0 && ThreadLocalRandom.current().nextDouble() < chance;
    }

    /**
     * Scales an island grant by the player's buff (BUFF stage, applied
     * exactly once per grant; probabilistic rounding keeps fractions fair).
     */
    private int scaleFor(final Player player, final String buffId, final int base) {
        return IslandBuffService.scaleCount(base, plugin.islandBuffs().mult(player, buffId));
    }

    /** Rare/treasure chance with island-luck applied (capped at 95%). */
    private boolean luckRoll(final Player player, final double chance) {
        return roll(Math.min(0.95, chance * plugin.islandBuffs().luckMult(player)));
    }

    /** Activity buff id for a role category (null never matches a buff). */
    private static String buffForCategory(final RoleCategory category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case MINING -> "mining-boost";
            case FARMING -> "farming-boost";
            case FISHING -> "fishing-boost";
            case SLAYING -> "slaying-boost";
            case LOGGING -> "logging-boost";
        };
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
        final int fortune = tierOf(island, "mining-fortune");
        if (fortune > 0 && roll(fortune
                * cfgPercent("mining-fortune", "chance-percent-per-level", 10) / 100.0)) {
            bonusCopies(block, scaleFor(player, "mining-boost", 1));
        }
        tryCurrency(player, island, "mining-tokens", Currency.SKY_TOKENS);
        tryCurrency(player, island, "mining-credits", Currency.CREDITS);
        final int speed = hasteAmpForTier(tierOf(island, "mining-speed"));
        if (speed >= 0) {
            plugin.enchantEngine().applyPotion(player, PotionEffectType.HASTE, speed, 100);
        }
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
        final int fortune = tierOf(island, "logging-fortune");
        if (fortune > 0 && roll(fortune
                * cfgPercent("logging-fortune", "chance-percent-per-level", 10) / 100.0)) {
            block.getWorld().dropItemNaturally(block.getLocation(),
                    new ItemStack(block.getType(), scaleFor(player, "logging-boost", 1)));
        }
        final int treeYield = tierOf(island, "tree-yield");
        if (treeYield > 0 && roll(treeYield
                * cfgPercent("tree-yield", "chance-percent-per-level", 10) / 100.0)) {
            block.getWorld().dropItemNaturally(block.getLocation(),
                    new ItemStack(block.getType(), scaleFor(player, "logging-boost", 2)));
        }
        tryCurrency(player, island, "logging-tokens", Currency.SKY_TOKENS);
        tryCurrency(player, island, "logging-credits", Currency.CREDITS);
        final int rare = tierOf(island, "rare-wood");
        if (rare > 0 && luckRoll(player, rare * cfgPercent("rare-wood", "chance-percent-per-level", 5) / 100.0)) {
            grantTable(player, "rare-wood", "rewards", RoleCategory.LOGGING, false);
        }
        final int growth = tierOf(island, "tree-growth");
        if (growth > 0 && roll(growth
                * cfgPercent("tree-growth", "chance-percent-per-level", 15) / 100.0)) {
            regrowTrunk(block);
        }
    }

    /** Drops N extra copies of the block's BASE drops (no tool — no Fortune double-dip). */
    private static void bonusCopies(final Block block, final int copies) {
        for (final ItemStack stack : block.getDrops()) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            for (int copy = 0; copy < copies; copy++) {
                block.getWorld().dropItemNaturally(block.getLocation(), stack.clone());
            }
        }
    }

    /** The felled trunk replants a sapling and bonemeals it (island regrowth). */
    private void regrowTrunk(final Block origin) {
        final Material sapling = EnchantBlocks.saplingFor(origin.getType());
        if (sapling == null) {
            return;
        }
        final Location at = origin.getLocation();
        final Block below = origin.getRelative(BlockFace.DOWN);
        plugin.tasks().runLater(() -> {
            final Block target = at.getBlock();
            if (!target.getType().isAir() || below.getType().isAir() || below.isLiquid()) {
                return;
            }
            target.setType(sapling);
            target.applyBoneMeal(BlockFace.UP);
        }, 2L);
    }

    /** One island token/credit proc: chance roll, then the boosted currency funnel. */
    private void tryCurrency(final Player player, final Island island, final String track,
            final Currency currency) {
        final int tier = tierOf(island, track);
        if (tier <= 0) {
            return;
        }
        if (!roll(tier * cfgPercent(track, "chance-percent-per-level", 5) / 100.0)) {
            return;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        final long amount = Math.max(1L, cfgInt(track, "amount", currency == Currency.CREDITS ? 2 : 1));
        plugin.enchantEngine().grantCurrency(player, profile, currency, amount, profile.roleId(), true);
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
        final Location at = victim.getLocation();
        if (at.getWorld() != null) {
            plugin.islands().islandAt(at.getWorld().getName(), at.getBlockX(), at.getBlockZ())
                    .ifPresent(island -> mobCounters.computeIfPresent(island.islandId(),
                            (key, count) -> count > 0 ? count - 1 : 0));
        }
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
        final CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL
                && reason != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) {
            return; // spawner/custom/egg spawns belong to their own systems — never capped here
        }
        final Location location = event.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        final var island = plugin.islands().islandAt(
                location.getWorld().getName(), location.getBlockX(), location.getBlockZ());
        if (island.isEmpty()) {
            return;
        }
        final Island value = island.get();
        // The cap only manages islands that bought the track (tier 0 =
        // vanilla behaviour, never a nerf); buying in opts into a
        // raised, enforced cap.
        final int capTier = tierOf(value, "mob-cap");
        final int cap = cfgInt("mob-cap", "base-cap", 10)
                + capTier * cfgInt("mob-cap", "cap-per-level", 5);
        if (capTier > 0) {
            int count = mobCounters.getOrDefault(value.islandId(), 0);
            if (count >= cap) {
                // Self-healing verify scan: chunk unloads/despawns leak the
                // estimate upward, so confirm with a real (rare, bounded)
                // entity census before denying a spawn.
                count = countIslandMobs(value);
                mobCounters.put(value.islandId(), count);
                if (count >= cap) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        mobCounters.merge(value.islandId(), 1, Integer::sum);
        final int rate = tierOf(value, "mob-rate");
        if (rate <= 0
                || !roll(rate * cfgPercent("mob-rate", "chance-percent-per-level", 10) / 100.0)) {
            return;
        }
        if (capTier > 0 && mobCounters.getOrDefault(value.islandId(), 0) >= cap) {
            return; // twins never bypass the cap
        }
        location.getWorld().spawnEntity(location, event.getEntityType());
        mobCounters.merge(value.islandId(), 1, Integer::sum);
    }

    /** Bounded census of living non-player mobs inside the island border. */
    private int countIslandMobs(final Island island) {
        final World world = plugin.getServer().getWorld(island.worldName());
        if (world == null) {
            return 0;
        }
        final int half = Math.max(1, plugin.islands().effectiveBorder(island) / 2);
        final Location center = new Location(world, island.centerX(), 64.0, island.centerZ());
        int count = 0;
        for (final Entity entity : world.getNearbyEntities(center, half, 256, half)) {
            if (entity instanceof LivingEntity && !(entity instanceof Player)
                    && plugin.islands().containsBlock(island,
                            entity.getLocation().getBlockX(), entity.getLocation().getBlockZ())) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ island reward tables

    /**
     * Rolls an island reward table once (every entry rolls independently;
     * all hits granted through the standard funnels so enchant boosts
     * stack intentionally). Spawner tables grant ITEM/XP only.
     */
    private void grantTable(final Player player, final String track, final String tableKey,
            final RoleCategory category, final boolean spawnerSafe) {
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        final String categoryBuff = buffForCategory(category);
        for (final RewardRoll roll : rewardTable(track, tableKey, spawnerSafe)) {
            if (spawnerSafe
                    && roll.type() != RewardRoll.RewardType.ITEM
                    && roll.type() != RewardRoll.RewardType.XP) {
                continue; // belt & braces: the parser already rejects these
            }
            if (!roll(roll.chance())) {
                continue;
            }
            final int amount = roll.rollAmount(plugin.enchantEngine().random());
            switch (roll.type()) {
                case ITEM -> {
                    final Material material = Material.matchMaterial(roll.material());
                    if (material == null || !material.isItem()) {
                        continue;
                    }
                    final int granted = categoryBuff == null
                            ? amount
                            : scaleFor(player, categoryBuff, amount);
                    plugin.enchantEngine().giveOrDrop(player, new ItemStack(material, granted));
                }
                case TOKENS -> plugin.enchantEngine().grantCurrency(
                        player, profile, Currency.SKY_TOKENS, amount, profile.roleId(), true);
                case CREDITS -> plugin.enchantEngine().grantCurrency(
                        player, profile, Currency.CREDITS, amount, profile.roleId(), true);
                case XP -> plugin.roles().awardCategoryXp(player, profile, category, amount);
                case KEY -> {
                    plugin.keys().giveKeys(player, roll.keyId(), amount);
                    final String name = plugin.keys().key(roll.keyId())
                            .map(key -> ColorUtil.colorize(key.display()))
                            .orElse(roll.keyId());
                    plugin.messages().sendPrefixed(player, "key.received",
                            Map.of("amount", String.valueOf(amount), "name", name));
                }
                case SOULS -> plugin.enchantEngine().grantSouls(player, profile, amount, profile.roleId());
            }
        }
    }

    /** Parsed island reward table (cached; spawner tables reject economy entries). */
    private List<RewardRoll> rewardTable(final String track, final String tableKey,
            final boolean spawnerSafe) {
        return tableCache.computeIfAbsent(track + ":" + tableKey, ignored -> {
            final List<RewardRoll> table = new ArrayList<>();
            final List<Map<?, ?>> raw =
                    plugin.getConfig().getMapList("island.upgrades." + track + "." + tableKey);
            final String where = "island.upgrades." + track + "." + tableKey;
            final List<String> errors = new ArrayList<>();
            for (final Object entry : raw) {
                RewardRoll.parse(entry, errors, where).ifPresent(table::add);
            }
            for (final String error : errors) {
                plugin.getLogger().warning("[island-upgrades] " + error);
            }
            if (spawnerSafe) {
                final List<RewardRoll> safe = new ArrayList<>();
                for (final RewardRoll roll : table) {
                    if (roll.type() == RewardRoll.RewardType.ITEM
                            || roll.type() == RewardRoll.RewardType.XP) {
                        safe.add(roll);
                    } else {
                        plugin.getLogger().warning("[island-upgrades] " + where
                                + ": " + roll.type() + " entries are rejected — spawner tables"
                                + " grant items/XP only (spawner farms must never print economy).");
                    }
                }
                return List.copyOf(safe);
            }
            return List.copyOf(table);
        });
    }
}
