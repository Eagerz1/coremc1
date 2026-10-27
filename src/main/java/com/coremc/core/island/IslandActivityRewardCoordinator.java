package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.enchant.EnchantBlocks;
import com.coremc.core.enchant.RewardRoll;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/** Owns shared island upgrade rolls, currency rewards, and configured reward tables. */
public final class IslandActivityRewardCoordinator {

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
    /** Parsed island reward tables, cleared on /coremc reload. */
    private final Map<String, List<RewardRoll>> tableCache = new ConcurrentHashMap<>();

    public IslandActivityRewardCoordinator(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    public void clearCache() {
        tableCache.clear();
    }

    public int tierOf(final Island island, final String track) {
        return island.upgrades().getOrDefault(track, 0);
    }

    public int cfgInt(final String track, final String key, final int fallback) {
        return plugin.getConfig().getInt("island.upgrades." + track + "." + key, fallback);
    }

    public int cfgPercent(final String track, final String key, final int fallback) {
        return Math.max(0, cfgInt(track, key, fallback));
    }

    public boolean roll(final double chance) {
        return chance > 0.0 && ThreadLocalRandom.current().nextDouble() < chance;
    }

    /** Scales an island grant by the player's activity buff with the existing fractional rounding. */
    public int scaleFor(final Player player, final String buffId, final int base) {
        return IslandBuffService.scaleCount(base, plugin.islandBuffs().mult(player, buffId));
    }

    /** Rare/treasure chance with island-luck applied and capped at 95%. */
    public boolean luckRoll(final Player player, final double chance) {
        return roll(Math.min(0.95, chance * plugin.islandBuffs().luckMult(player)));
    }

    /** Drops extra copies of the block's base drops without applying tool Fortune. */
    public void bonusCopies(final Block block, final int copies) {
        for (final ItemStack stack : block.getDrops()) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            for (int copy = 0; copy < copies; copy++) {
                block.getWorld().dropItemNaturally(block.getLocation(), stack.clone());
            }
        }
    }

    /** Applies mining fortune, currency, and haste upgrades after block-break admission. */
    public void miningProcs(final Player player, final Island island, final Block block) {
        final int fortune = tierOf(island, "mining-fortune");
        if (fortune > 0 && roll(fortune
                * cfgPercent("mining-fortune", "chance-percent-per-level", 10) / 100.0)) {
            bonusCopies(block, scaleFor(player, "mining-boost", 1));
        }
        tryCurrency(player, island, "mining-tokens", Currency.SKY_TOKENS);
        tryCurrency(player, island, "mining-credits", Currency.CREDITS);
        final int speed = IslandActivityEffects.hasteAmpForTier(tierOf(island, "mining-speed"));
        if (speed >= 0) {
            plugin.enchantEngine().applyPotion(player, PotionEffectType.HASTE, speed, 100);
        }
    }

    /** Applies farming fortune, crop yield, currency, and harvest haste upgrades. */
    public void farmingProcs(final Player player, final Island island, final Block block) {
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
        final int speed = IslandActivityEffects.hasteAmpForTier(tierOf(island, "harvest-speed"));
        if (speed >= 0) {
            plugin.enchantEngine().applyPotion(player, PotionEffectType.HASTE, speed, 100);
        }
    }

    /** Applies logging fortune, tree yield, rare rewards, currency, and regrowth upgrades. */
    public void loggingProcs(final Player player, final Island island, final Block block) {
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

    /** The felled trunk replants a sapling and bonemeals it. */
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
    public void tryCurrency(final Player player, final Island island, final String track,
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

    /** Rolls one island reward table; entries remain independent and spawner-safe tables stay item/XP only. */
    public void grantTable(final Player player, final String track, final String tableKey,
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

    private List<RewardRoll> rewardTable(final String track, final String tableKey, final boolean spawnerSafe) {
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
}
