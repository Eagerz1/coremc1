package com.coremc.core.gens;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the /gens menu — same pattern as the shop's
 * {@code ShopMenu}, the island's {@code IslandMenu} and the spawner
 * menu. The listener uses it to recognise (and protect) the window.
 */
public final class GensMenuHolder implements InventoryHolder {

    private Inventory inventory;

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
