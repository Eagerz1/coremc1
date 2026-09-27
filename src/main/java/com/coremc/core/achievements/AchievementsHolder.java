package com.coremc.core.achievements;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the Achievement windows, carrying the view state
 * (which view, which category, which page) so the listener never has
 * to parse an inventory title.
 */
public final class AchievementsHolder implements InventoryHolder {

    /** Which Achievement window this is. */
    public enum View {
        /** Category overview. */
        ROOT,
        /** One category's achievements, paged. */
        CATEGORY,
        /** Every secret achievement, revealed only once earned. */
        SECRETS
    }

    private final View view;
    private final AchievementCategory category;
    private final int page;

    private Inventory inventory;

    public AchievementsHolder(final View view, final AchievementCategory category, final int page) {
        this.view = view;
        this.category = category;
        this.page = Math.max(0, page);
    }

    public View view() {
        return view;
    }

    public AchievementCategory category() {
        return category;
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
