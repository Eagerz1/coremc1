package com.coremc.core.guide;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Marker holder for /help guide windows. */
public final class HelpMenu implements InventoryHolder {

    public enum Kind {
        ROOT, PAGE, COMMANDS
    }

    private final Kind kind;
    private final String categoryId;
    private Inventory inventory;

    public HelpMenu(final Kind kind, final String categoryId) {
        this.kind = kind;
        this.categoryId = categoryId;
    }

    public Kind kind() {
        return kind;
    }

    public String categoryId() {
        return categoryId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
