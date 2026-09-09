package com.coremc.core.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * InventoryHolder binding an inventory to its {@link Gui}. This is how
 * the click dispatcher finds the right handler without any per-player
 * tracking maps (nothing to leak, cleanup is implicit on close).
 */
public final class GuiHolder implements InventoryHolder {

    private final Gui gui;
    private Inventory inventory;

    public GuiHolder(final Gui gui) {
        this.gui = gui;
    }

    public Gui gui() {
        return gui;
    }

    void bind(final Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
