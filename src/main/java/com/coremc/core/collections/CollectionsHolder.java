package com.coremc.core.collections;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the Collection windows — the same pattern as the
 * shop, island, spawner and generator menus. It also carries the view
 * state (which view, which category, which entry, which page) so the
 * listener never has to parse an inventory title.
 */
public final class CollectionsHolder implements InventoryHolder {

    /** Which Collection window this is. */
    public enum View {
        /** Category overview. */
        ROOT,
        /** One category's entries, paged. */
        CATEGORY,
        /** One entry's milestone tiers. */
        ENTRY,
        /** Collection-locked recipes, paged. */
        RECIPES,
        /** Rewards held safely until they can be delivered. */
        PENDING
    }

    private final View view;
    private final CollectionCategory category;
    private final String entryId;
    private final int page;

    private Inventory inventory;

    public CollectionsHolder(final View view, final CollectionCategory category,
                             final String entryId, final int page) {
        this.view = view;
        this.category = category;
        this.entryId = entryId;
        this.page = Math.max(0, page);
    }

    public View view() {
        return view;
    }

    public CollectionCategory category() {
        return category;
    }

    public String entryId() {
        return entryId;
    }

    public int page() {
        return page;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory created) {
        this.inventory = created;
    }
}
