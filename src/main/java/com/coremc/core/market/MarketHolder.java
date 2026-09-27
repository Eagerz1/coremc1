package com.coremc.core.market;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the Black Market menus (the CoreMC menu
 * pattern): carries the page, the EXACT rotation id the page was
 * rendered for (a purchase click sends it along, so a click on a
 * stale page can never buy from a newer rotation) and the
 * slot → offer mapping — clicks are never routed by display names.
 */
public final class MarketHolder implements InventoryHolder {

    /** Which market page is open. */
    public enum Page {
        CLOSED, OFFERS, AUCTION
    }

    private final Page page;
    private final String rotationId;
    private final Map<Integer, String> offersBySlot = new HashMap<>();
    private final Map<Integer, Integer> bidButtonsBySlot = new HashMap<>();
    private Inventory inventory;

    public MarketHolder(final Page page, final String rotationId) {
        this.page = page;
        this.rotationId = rotationId;
    }

    public Page page() {
        return page;
    }

    /** The rotation this page was rendered for (null when closed). */
    public String rotationId() {
        return rotationId;
    }

    void putOffer(final int slot, final String offerId) {
        offersBySlot.put(slot, offerId);
    }

    public String offerAt(final int slot) {
        return offersBySlot.get(slot);
    }

    void putBidButton(final int slot, final int buttonIndex) {
        bidButtonsBySlot.put(slot, buttonIndex);
    }

    /** The configured bid-button index at a slot, or null. */
    public Integer bidButtonAt(final int slot) {
        return bidButtonsBySlot.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
