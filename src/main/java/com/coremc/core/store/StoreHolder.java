package com.coremc.core.store;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the /store menus — the same pattern as every other
 * CoreMC menu. Carries the open page and the slot → entry mapping the
 * listener routes clicks through (never the item display names).
 */
public final class StoreHolder implements InventoryHolder {

    /** Which /store page is open. */
    public enum Page {
        ROOT, KEYS, LOOTBOXES, BUNDLES
    }

    /** What kind of thing a clickable slot sells. */
    public enum Kind {
        KEY, LOOTBOX, BUNDLE
    }

    /** One clickable store entry: what it is + its config id. */
    public record Entry(Kind kind, String id) {
    }

    private final Page page;
    private final Map<Integer, Entry> entries = new HashMap<>();
    private Inventory inventory;

    public StoreHolder(final Page page) {
        this.page = page;
    }

    public Page page() {
        return page;
    }

    void put(final int slot, final Entry entry) {
        entries.put(slot, entry);
    }

    public Entry entryAt(final int slot) {
        return entries.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
