package com.coremc.core.shop;

import java.util.List;
import org.bukkit.Material;

/**
 * A shop category ("Building Blocks", "Crops", ...): a display name,
 * an icon shown in the shop root menu and its priced item list.
 * Section order in shop.yml is preserved for the root menu layout.
 *
 * @param id    stable config id (e.g. {@code building})
 * @param name  display name shown in titles and the root menu
 * @param icon  root-menu icon material
 * @param items priced entries, in page order
 */
public record ShopSection(String id, String name, Material icon, List<ShopItem> items) {

    public ShopSection {
        items = List.copyOf(items);
    }

    public int itemCount() {
        return items.size();
    }

    public ShopItem item(final int index) {
        return items.get(index);
    }
}
