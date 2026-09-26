package com.coremc.core.gens;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the generator management window. It carries only
 * the block key of the generator it was opened on, so every click
 * re-resolves the live registration instead of trusting a snapshot
 * (the stack may have changed, or the generator may be gone).
 */
public final class GeneratorManageHolder implements InventoryHolder {

    private final String entryKey;
    private Inventory inventory;

    public GeneratorManageHolder(final String entryKey) {
        this.entryKey = entryKey;
    }

    /** Block key of the generator this window manages. */
    public String entryKey() {
        return entryKey;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
