package com.coremc.core.shop;

import com.coremc.core.config.MessageService;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Settles the /sell window: when it closes, every sellable item is
 * paid for at its shop price (times the player's rank multiplier) and
 * everything else — custom progression items, catalogue-refused
 * materials — is handed back (dropped at the player's feet when their
 * inventory is full). Also runs on plugin disable so a restart never
 * swallows items sitting in an open window.
 */
public final class SellListener implements Listener {

    private final ShopConfig config;
    private final EconomyService economy;
    private final MessageService messages;
    /** Rank sell bonus (Core and up earn a multiple on every sale). */
    private final com.coremc.core.rank.RankService ranks;

    public SellListener(final ShopConfig config, final EconomyService economy,
                        final MessageService messages,
                        final com.coremc.core.rank.RankService ranks) {
        this.config = config;
        this.economy = economy;
        this.messages = messages;
        this.ranks = ranks;
    }

    @EventHandler
    public void onInventoryClose(final InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SellMenuHolder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        settle(player, event.getInventory());
    }

    /**
     * Pays for and clears a sell window's contents. Called when the
     * window closes and on plugin disable.
     */
    void settle(final Player player, final Inventory inventory) {
        final SellService.Tally tally = SellService.tally(inventory.getContents(),
                ShopTransactions::isCustomItem, config::sellPrice);
        inventory.clear();

        if (tally.soldItems() > 0) {
            // ranked players earn a multiple on every sale (Core x1.05 and up)
            final double multiplier = ranks == null ? 1.0 : ranks.multiplierOf(player.getUniqueId());
            final double credit = Money.round(tally.total() * multiplier);
            economy.deposit(player.getUniqueId(), credit);
            player.sendMessage(messages.prefix() + messages.get("sell.gui-sold", Map.of(
                    "count", String.valueOf(tally.soldItems()),
                    "word", tally.soldItems() == 1 ? "item" : "items",
                    "money", Money.format(credit, config.currencySymbol()))));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        }

        int returnedCount = 0;
        for (final ItemStack stack : tally.returned()) {
            returnedCount += stack.getAmount();
            final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (final ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        if (returnedCount > 0) {
            messages.sendPrefixed(player,
                    returnedCount == 1 ? "sell.gui-returned-one" : "sell.gui-returned-many",
                    Map.of("count", String.valueOf(returnedCount)));
        }
    }

    /** On plugin disable: settle every open sell window so items are never lost. */
    public void closeAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof SellMenuHolder) {
                settle(player, top);
                player.closeInventory();
            }
        }
    }
}
