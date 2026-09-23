package com.coremc.core.island;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for an island top leaderboard board (the 54-slot
 * window); carries the category currently shown so the click
 * controller knows what the back button returns to.
 */
public final class IsTopBoardHolder implements InventoryHolder {

    private final IslandTop.Category category;
    private Inventory inventory;

    public IsTopBoardHolder(final IslandTop.Category category) {
        this.category = category;
    }

    /** The leaderboard currently displayed. */
    public IslandTop.Category category() {
        return category;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
