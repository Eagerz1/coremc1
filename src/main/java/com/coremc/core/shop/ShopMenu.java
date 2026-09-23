package com.coremc.core.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for a CoreMC shop GUI. Carries the window state
 * (root menu vs. a section page, which section, which page) so the
 * click controller knows what a clicked slot means without parsing
 * item names.
 */
public final class ShopMenu implements InventoryHolder {

    /** Which shop window this is. */
    public enum Kind {
        ROOT,
        SECTION
    }

    private final Kind kind;
    private final String sectionId;
    private final int page;
    private Inventory inventory;

    private ShopMenu(final Kind kind, final String sectionId, final int page) {
        this.kind = kind;
        this.sectionId = sectionId;
        this.page = page;
    }

    static ShopMenu root() {
        return new ShopMenu(Kind.ROOT, null, 0);
    }

    static ShopMenu section(final String sectionId, final int page) {
        return new ShopMenu(Kind.SECTION, sectionId, page);
    }

    public Kind kind() {
        return kind;
    }

    /** Section config id when {@link #kind()} is {@link Kind#SECTION}, else {@code null}. */
    public String sectionId() {
        return sectionId;
    }

    /** 0-based page number for section windows. */
    public int page() {
        return page;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
