package com.coremc.core.store;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for reward preview windows (crate and lootbox odds).
 * Remembers which store page the viewer came from so Back returns
 * there.
 */
public final class PreviewHolder implements InventoryHolder {

    private final StoreHolder.Page backPage;
    private Inventory inventory;

    public PreviewHolder(final StoreHolder.Page backPage) {
        this.backPage = backPage;
    }

    public StoreHolder.Page backPage() {
        return backPage;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
