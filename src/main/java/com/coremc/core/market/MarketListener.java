package com.coremc.core.market;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Click controller for the Black Market menus. EVERYTHING that could
 * move an item is cancelled — top clicks, shift-clicks, number-key
 * swaps, double-click collects and drags — and clicks route by the
 * holder's slot map, never by item names. Purchases re-render the
 * page so stock and limits are always live.
 */
public final class MarketListener implements Listener {

    private final MarketGui gui;
    private final MarketService market;
    private final DarkAuctionService auction;
    private final java.util.function.LongSupplier clock;

    public MarketListener(final MarketGui gui, final MarketService market,
                          final DarkAuctionService auction,
                          final java.util.function.LongSupplier clock) {
        this.gui = gui;
        this.market = market;
        this.auction = auction;
        this.clock = clock;
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        final Object holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof MarketHolder marketHolder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // clicks in the player's own inventory: block anything that
        // could push items into the menu (or pull via double-click)
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.getClick().isShiftClick() || event.getClick() == ClickType.DOUBLE_CLICK
                    || event.getClick() == ClickType.NUMBER_KEY
                    || event.getClick() == ClickType.SWAP_OFFHAND) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        final int slot = event.getSlot();
        final long now = clock.getAsLong();
        if (slot == com.coremc.core.store.StoreLayout.CLOSE_SLOT) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            player.closeInventory();
            return;
        }
        switch (marketHolder.page()) {
            case OFFERS -> {
                final String offerId = marketHolder.offerAt(slot);
                if (offerId == null) {
                    return;
                }
                // the click carries the EXACT rotation the page showed —
                // a stale page can never buy from a newer rotation
                market.purchase(player, marketHolder.rotationId(), offerId);
                gui.open(player, now);
            }
            case AUCTION -> {
                final Integer buttonIndex = marketHolder.bidButtonAt(slot);
                final AuctionEngine engine = auction.engine();
                if (buttonIndex == null || engine == null
                        || buttonIndex >= market.config().bidButtons().size()) {
                    return;
                }
                auction.bid(player, MarketGui.nextBidFor(
                        market.config().bidButtons().get(buttonIndex), engine));
                gui.openAuction(player, now);
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(final InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MarketHolder) {
            for (final int slot : event.getRawSlots()) {
                if (slot < event.getView().getTopInventory().getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        auction.onJoin(event.getPlayer());
    }
}
