package com.coremc.core.spawner;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the spawner menu GUI — same pattern as the shop's
 * {@code ShopMenu} and the island's {@code IslandMenu}. The listener
 * uses it to recognise (and protect) the menu window.
 */
public final class SpawnerMenuHolder implements InventoryHolder {

    private Inventory inventory;

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
