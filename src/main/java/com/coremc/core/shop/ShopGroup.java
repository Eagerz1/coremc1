package com.coremc.core.shop;

import java.util.List;
import org.bukkit.Material;

/**
 * A subcategory inside a grouped shop section ("Stone", "Wood", ...).
 *
 * <p>Grouped sections are how a big catalogue stays browsable: the
 * section window turns into a group picker, and each group paginates
 * like a small section of its own. Groups exist purely for browsing —
 * pricing and {@code /sell} valuation always work on the section's
 * flattened item list, so a material has one price no matter where it
 * is shown.</p>
 *
 * @param id    stable config id (e.g. {@code stone})
 * @param name  display name shown in the picker and window titles
 * @param icon  picker icon material (defaults to the group's first item)
 * @param items priced entries, in page order
 */
public record ShopGroup(String id, String name, Material icon, List<ShopItem> items) {

    public ShopGroup {
        items = List.copyOf(items);
    }

    public int itemCount() {
        return items.size();
    }

    public ShopItem item(final int index) {
        return items.get(index);
    }
}
