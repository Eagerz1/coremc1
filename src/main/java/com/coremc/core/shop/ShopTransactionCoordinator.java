package com.coremc.core.shop;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Owns the balance, inventory, and delivery ordering for shop transactions. */
public final class ShopTransactionCoordinator {

    private final CoreMCPlugin plugin;

    public ShopTransactionCoordinator(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Withdraws before delivery and refunds if neither inventory can accept the item.
     *
     * @return true on completed purchase
     */
    public boolean purchase(final Player player, final ShopEntry entry) {
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            plugin.messages().sendPrefixed(player, "shop.unavailable", Map.of());
            return false;
        }
        synchronized (profile) {
            final String price = String.format(Locale.ROOT, "%,d", entry.price());
            final String currencyName = entry.currency().displayName();
            final boolean free = entry.price() <= 0L; // withdraw(0) would throw by contract
            if (!free && !plugin.economy().withdraw(profile, entry.currency(), entry.price())) {
                plugin.messages().sendPrefixed(player, "shop.insufficient",
                        Map.of("price", price, "currency", currencyName));
                return false;
            }
            final ItemStack item = new ItemStack(entry.material(), entry.amount());
            final var meta = item.getItemMeta();
            if (meta != null && entry.display() != null) {
                meta.setDisplayName(ColorUtil.colorize(entry.display()));
                item.setItemMeta(meta);
            }
            final var delivery = com.coremc.core.util.ItemDelivery.deliverDetailed(player, item);
            if (delivery == com.coremc.core.util.ItemDelivery.Result.FAILED) {
                // nothing fit anywhere: the rollback restored both inventories, so refund the price.
                if (!free) {
                    plugin.economy().deposit(profile, entry.currency(), entry.price());
                }
                plugin.messages().sendPrefixed(player, "purchase.no-space", Map.of());
                return false;
            }
            if (delivery == com.coremc.core.util.ItemDelivery.Result.DELIVERED_TO_ENDER_CHEST) {
                plugin.messages().sendPrefixed(player, "shop.overflow", Map.of());
            }
            plugin.messages().sendPrefixed(player, "shop.bought", Map.of(
                    "item", ColorUtil.colorize(entry.display()),
                    "price", price,
                    "currency", currencyName));
            return true;
        }
    }

    /**
     * Deposits sale proceeds before consuming plain inventory stock.
     *
     * @return true when at least one item sold
     */
    public boolean sell(final Player player, final ShopEntry entry) {
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            plugin.messages().sendPrefixed(player, "shop.unavailable", Map.of());
            return false;
        }
        synchronized (profile) {
            final String itemName = ColorUtil.colorize(entry.display());
            if (entry.sellPrice() <= 0L) {
                plugin.messages().sendPrefixed(player, "shop.unsellable", Map.of("item", itemName));
                return false;
            }
            int count = 0;
            for (final ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == entry.material() && sellable(item)) {
                    count += item.getAmount();
                }
            }
            if (count <= 0) {
                plugin.messages().sendPrefixed(player, "shop.nothing-to-sell", Map.of("item", itemName));
                return false;
            }
            final double mult = plugin.islandBuffs().sellMult(player);
            final double raw = count * (double) entry.sellPrice() * mult;
            final long total = raw >= (double) Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, Math.round(raw));
            try {
                plugin.economy().deposit(profile, entry.currency(), total);
            } catch (final IllegalArgumentException overflow) {
                // Balance cannot hold the proceeds: inventory untouched, nothing sold.
                plugin.messages().sendPrefixed(player, "shop.overflowed", Map.of());
                return false;
            }
            int remaining = count;
            final var inventory = player.getInventory();
            final ItemStack[] contents = inventory.getContents();
            for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
                final ItemStack item = contents[slot];
                if (item == null || item.getType() != entry.material() || !sellable(item)) {
                    continue;
                }
                final int take = Math.min(remaining, item.getAmount());
                item.setAmount(item.getAmount() - take);
                remaining -= take;
                inventory.setItem(slot, item.getAmount() <= 0 ? null : item);
            }
            plugin.messages().sendPrefixed(player, "shop.sold", Map.of(
                    "amount", String.valueOf(count - remaining),
                    "item", itemName,
                    "price", String.format(Locale.ROOT, "%,d", total),
                    "currency", entry.currency().displayName()));
            return true;
        }
    }

    /** CoreMC-tagged, enchanted, or damaged items are never ordinary shop stock. */
    private static boolean sellable(final ItemStack item) {
        if (!item.getEnchantments().isEmpty()) {
            return false;
        }
        final var meta = item.getItemMeta();
        if (meta == null) {
            return true;
        }
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable && damageable.hasDamage()) {
            return false;
        }
        return meta.getPersistentDataContainer().isEmpty();
    }
}
