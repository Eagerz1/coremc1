package com.coremc.core.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for a CoreMC shop GUI. Carries the window state
 * (root menu, a flat section page, a grouped section's picker, or a
 * group page; which section/group, which page) so the click
 * controller knows what a clicked slot means without parsing item
 * names.
 */
public final class ShopMenu implements InventoryHolder {

    /** Which shop window this is. */
    public enum Kind {
        ROOT,
        SECTION,
        PICKER,
        GROUP
    }

    private final Kind kind;
    private final String sectionId;
    private final String groupId;
    private final int page;
    private Inventory inventory;

    private ShopMenu(final Kind kind, final String sectionId, final String groupId, final int page) {
        this.kind = kind;
        this.sectionId = sectionId;
        this.groupId = groupId;
        this.page = page;
    }

    static ShopMenu root() {
        return new ShopMenu(Kind.ROOT, null, null, 0);
    }

    static ShopMenu section(final String sectionId, final int page) {
        return new ShopMenu(Kind.SECTION, sectionId, null, page);
    }

    static ShopMenu picker(final String sectionId) {
        return new ShopMenu(Kind.PICKER, sectionId, null, 0);
    }

    static ShopMenu group(final String sectionId, final String groupId, final int page) {
        return new ShopMenu(Kind.GROUP, sectionId, groupId, page);
    }

    public Kind kind() {
        return kind;
    }

    /** Section config id for section/picker/group windows, else {@code null}. */
    public String sectionId() {
        return sectionId;
    }

    /** Group config id when {@link #kind()} is {@link Kind#GROUP}, else {@code null}. */
    public String groupId() {
        return groupId;
    }

    /** 0-based page number for section and group windows. */
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
