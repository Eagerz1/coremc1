package com.coremc.core.shop;

import java.util.List;
import org.bukkit.Material;

/**
 * A shop category ("Building", "Farming", ...): a display name, an
 * icon shown in the shop root menu and its priced item list. Section
 * order in shop.yml is preserved for the root menu layout.
 *
 * <p>A section is either <b>flat</b> (a plain {@code items} list, browsed
 * as paginated pages) or <b>grouped</b> (a {@code groups} mapping of
 * subcategories, browsed as a group picker plus per-group pages).
 * For grouped sections {@link #items()} is still the complete flattened
 * catalogue in group order, so pricing, {@code /sell} valuation and the
 * root-menu item count work the same for both shapes.</p>
 *
 * @param id     stable config id (e.g. {@code building})
 * @param name   display name shown in titles and the root menu
 * @param icon   root-menu icon material
 * @param items  priced entries in catalogue order (groups concatenated)
 * @param groups subcategories in picker order; empty for flat sections
 */
public record ShopSection(String id, String name, Material icon,
                          List<ShopItem> items, List<ShopGroup> groups) {

    public ShopSection {
        items = List.copyOf(items);
        groups = List.copyOf(groups);
    }

    /** Flat-section constructor (no subcategories). */
    public ShopSection(final String id, final String name, final Material icon,
                       final List<ShopItem> items) {
        this(id, name, icon, items, List.of());
    }

    /** Total priced items across the section (flat list, all groups). */
    public int itemCount() {
        return items.size();
    }

    public ShopItem item(final int index) {
        return items.get(index);
    }

    /** True when this section is browsed through a group picker. */
    public boolean grouped() {
        return !groups.isEmpty();
    }

    /** Subcategories in picker order; empty for flat sections. */
    public List<ShopGroup> groups() {
        return groups;
    }

    /** Group lookup by config id, or {@code null}. */
    public ShopGroup group(final String groupId) {
        for (final ShopGroup group : groups) {
            if (group.id().equalsIgnoreCase(groupId)) {
                return group;
            }
        }
        return null;
    }
}
