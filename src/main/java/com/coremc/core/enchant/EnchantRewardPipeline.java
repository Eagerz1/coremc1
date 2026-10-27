package com.coremc.core.enchant;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import com.coremc.core.util.ColorUtil;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Owns enchant currency, soul, reward-table, and item-delivery payouts. */
final class EnchantRewardPipeline {

    private final CoreMCPlugin plugin;
    private final EnchantEngine engine;

    EnchantRewardPipeline(final CoreMCPlugin plugin, final EnchantEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    long grantCurrency(final Player player, final PlayerProfile profile, final Currency currency,
            final long base, final String roleKey, final boolean applyCombo) {
        if (base <= 0L) {
            return 0L;
        }
        final String target = switch (currency) {
            case SKY_TOKENS -> "TOKENS";
            case CREDITS -> "CREDITS";
            case MONEY -> "MONEY";
        };
        double mult = plugin.enchants().passiveMultiplier(profile, roleKey, target);
        if (applyCombo) {
            mult *= engine.comboMultiplier(profile, roleKey, "CURRENCY");
        }
        // Island currency buffs apply in the same grant funnel as before.
        mult *= plugin.islandBuffs().currencyMult(player, currency);
        final long total = Math.max(1L, Math.round(base * mult));
        try {
            plugin.economy().deposit(profile, currency, total);
        } catch (final IllegalArgumentException overflow) {
            plugin.getLogger().fine("Currency grant overflow for " + player.getName() + ": " + total);
            return 0L;
        }
        return total;
    }

    long grantSouls(final Player player, final PlayerProfile profile, final long base,
            final String roleKey) {
        if (base <= 0L) {
            return 0L;
        }
        final double mult = plugin.enchants().passiveMultiplier(profile, roleKey, "SOULS")
                * engine.comboMultiplier(profile, roleKey, "SOULS");
        final long total = Math.max(1L, Math.round(base * mult));
        profile.souls(profile.souls() > Long.MAX_VALUE - total ? Long.MAX_VALUE : profile.souls() + total);
        plugin.playerData().markDirty(player.getUniqueId());
        return total;
    }

    void grantRewards(final Player player, final PlayerProfile profile, final Enchant enchant,
            final String roleKey, final RoleCategory xpCategory) {
        for (final RewardRoll roll : plugin.enchants().rewardTable(enchant)) {
            if (!engine.roll(roll.chance())) {
                continue;
            }
            final int amount = roll.rollAmount(engine.random());
            switch (roll.type()) {
                case ITEM -> {
                    final Material material = engine.resolveMaterial(roll.material(), null);
                    if (material == null) {
                        engine.warnOnce(enchant.id() + ":material:" + roll.material(),
                                "Unknown reward material '" + roll.material() + "' — skipped.");
                        continue;
                    }
                    giveOrDrop(player, new ItemStack(material, amount));
                }
                case TOKENS -> grantCurrency(player, profile, Currency.SKY_TOKENS, amount, roleKey, true);
                case CREDITS -> grantCurrency(player, profile, Currency.CREDITS, amount, roleKey, true);
                case XP -> plugin.roles().awardCategoryXp(player, profile, xpCategory, amount);
                case KEY -> {
                    plugin.keys().giveKeys(player, roll.keyId(), amount);
                    final String name = plugin.keys().key(roll.keyId())
                            .map(key -> ColorUtil.colorize(key.display()))
                            .orElse(roll.keyId());
                    plugin.messages().sendPrefixed(player, "key.received",
                            Map.of("amount", String.valueOf(amount), "name", name));
                }
                case SOULS -> grantSouls(player, profile, amount, roleKey);
            }
        }
    }

    void giveOrDrop(final Player player, final ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        player.getInventory().addItem(stack).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }

    void collectOrDrop(final Player player, final List<ItemStack> drops, final String fallback,
            final Location dropAt) {
        for (final ItemStack stack : drops) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            if ("DESPAWN".equalsIgnoreCase(fallback)) {
                continue;
            }
            leftover.values().forEach(rest -> player.getWorld().dropItemNaturally(dropAt, rest));
        }
    }

    void grantCurrencies(final Player player, final PlayerProfile profile, final String roleKey,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.CURRENCY) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final Currency currency = engine.currencyValue(enchant.values(), "currency", enchant.currency());
            final long base = Math.max(1L, (long) Math.floor(enchant.valueAt(owned.level())));
            grantCurrency(player, profile, currency, base, roleKey, true);
        }
    }

    void grantTables(final Player player, final PlayerProfile profile, final String roleKey,
            final List<EnchantService.EnchantLevel> active, final RoleCategory xpCategory) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            final boolean table = enchant.effect() == EnchantEffect.REWARD_TABLE
                    || enchant.effect() == EnchantEffect.FISH_BONUS
                            && "TREASURE".equalsIgnoreCase(EnchantEngine.stringValue(enchant.values(), "mode", ""));
            if (!table) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            grantRewards(player, profile, enchant, roleKey, xpCategory);
        }
    }
}
