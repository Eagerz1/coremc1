package com.coremc.core.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the /sell window: a plain double chest the player
 * drops items into. The window itself is inert (no click cancelling —
 * items are meant to move); {@link SellListener} settles up when it
 * closes.
 */
public final class SellMenuHolder implements InventoryHolder {

    private Inventory inventory;

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
