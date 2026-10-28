package com.coremc.core.crate;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.ItemDelivery;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Owns the key/pity/reward transaction for opening one crate. */
final class CrateOpeningCoordinator {

    private final CoreMCPlugin plugin;

    CrateOpeningCoordinator(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    boolean open(final Player player, final CrateDefinition crate) {
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            plugin.messages().sendPrefixed(player, "shop.unavailable", Map.of());
            return false;
        }
        synchronized (profile) {
            String keyUsed = null;
            for (final String keyId : crate.keys()) {
                if (plugin.keys().countKeys(player, keyId) > 0) {
                    keyUsed = keyId;
                    break;
                }
            }
            if (keyUsed == null) {
                final String keyName = plugin.keys().key(crate.keys().get(0))
                        .map(key -> ColorUtil.colorize(key.display()))
                        .orElse(crate.keys().get(0));
                plugin.messages().sendPrefixed(player, "crate.no-key", Map.of(
                        "key", keyName, "crate", ColorUtil.colorize(crate.display())));
                return false;
            }

            final long seen = profile.statOf(crate.pityStatKey()) + 1L;
            final boolean pityDue = crate.pityReward() != null && crate.pityCount() > 0
                    && seen >= crate.pityCount();
            final CrateReward reward = pityDue
                    ? crate.pityReward()
                    : CrateService.roll(crate, ThreadLocalRandom.current().nextDouble());
            if (reward == null) {
                plugin.getLogger().warning("[crates] crate '" + crate.id()
                        + "' has an empty pool — open refused.");
                plugin.messages().sendPrefixed(player, "crate.broken", Map.of());
                return false;
            }

            final ItemStack preResolved = preResolve(reward);
            if (reward.type() == CrateReward.RewardType.CURRENCY
                    && !plugin.economy().fitsDeposit(
                            profile, Currency.valueOf(reward.currency()), reward.max())) {
                plugin.messages().sendPrefixed(player, "economy.error.too-large", Map.of());
                return false;
            }
            if (preResolved == null && needsItem(reward)) {
                plugin.getLogger().warning("[crates] crate '" + crate.id()
                        + "' reward no longer resolves — refused.");
                plugin.messages().sendPrefixed(player, "crate.broken", Map.of());
                return false;
            }

            profile.setStat(crate.pityStatKey(),
                    (pityDue || CrateReward.isJackpot(reward.rarity())) ? 0L : seen);
            plugin.playerData().markDirty(profile.uuid());
            plugin.keys().takeKeys(player, keyUsed, 1);
            final String actual = grant(player, profile, reward, preResolved);
            plugin.messages().sendPrefixed(player, pityDue ? "crate.pity" : "crate.opened", Map.of(
                    "crate", ColorUtil.colorize(crate.display()),
                    "reward", actual));
            return true;
        }
    }

    private static boolean needsItem(final CrateReward reward) {
        return reward.type() == CrateReward.RewardType.SPAWNER
                || reward.type() == CrateReward.RewardType.GENERATOR
                || reward.type() == CrateReward.RewardType.ITEM;
    }

    private ItemStack preResolve(final CrateReward reward) {
        return switch (reward.type()) {
            case SPAWNER -> plugin.spawners().mint(reward.refId()).orElse(null);
            case GENERATOR -> plugin.generators().mint(reward.refId()).orElse(null);
            case ITEM -> {
                final Material material = Material.matchMaterial(reward.material());
                yield material == null ? null : new ItemStack(material, Math.min(64, reward.amount()));
            }
            default -> null;
        };
    }

    private String grant(final Player player, final PlayerProfile profile, final CrateReward reward,
            final ItemStack preResolved) {
        switch (reward.type()) {
            case CURRENCY -> {
                final long amount = reward.min() >= reward.max()
                        ? reward.min()
                        : reward.min() + ThreadLocalRandom.current().nextLong(reward.max() - reward.min() + 1L);
                final Currency currency = Currency.valueOf(reward.currency());
                plugin.economy().deposit(profile, currency, amount);
                return CrateReward.rarityColor(reward.rarity())
                        + String.format(Locale.ROOT, "%,d", amount) + " " + currency.displayName();
            }
            case KEY -> {
                plugin.keys().giveKeys(player, reward.key(), reward.amount());
                return ColorUtil.colorize(reward.label());
            }
            default -> {
                final ItemStack stack = preResolved == null ? new ItemStack(Material.STONE) : preResolved.clone();
                stack.setAmount(reward.type() == CrateReward.RewardType.ITEM
                        ? Math.min(64, reward.amount()) : Math.max(1, stack.getAmount()));
                if (ItemDelivery.deliverDetailed(player, stack) == ItemDelivery.Result.FAILED) {
                    player.getWorld().dropItemNaturally(player.getLocation(), stack);
                    plugin.messages().sendPrefixed(player, "crate.dropped", Map.of());
                }
                return ColorUtil.colorize(reward.label());
            }
        }
    }
}
