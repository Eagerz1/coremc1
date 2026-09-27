package com.coremc.core.season;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Holder for /journey GUI pages. */
public final class SeasonJourneyMenu implements InventoryHolder {
    private final int page;
    private Inventory inventory;

    public SeasonJourneyMenu(final int page) {
        this.page = Math.max(0, page);
    }

    public int page() { return page; }

    @Override
    public Inventory getInventory() { return inventory; }

    void inventory(final Inventory inventory) { this.inventory = inventory; }
}
