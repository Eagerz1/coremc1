package com.coremc.core.shop;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * One 54-slot category page (double chest). Slot positions are hard
 * constants — the grid walks 5 rows of 9, entries sit at 10..16 / 19..25 /
 * 28..34 / 37..43 (4×7 = 28 items per page), navigation on the bottom row.
 *
 * Left-click buys, right-click sells matching inventory stock back at
 * the entry's sell price (when sellable; members on their own island
 * earn the sell-boost on top).
 */
public final class ShopCategoryGui implements Gui {

    /** Item slots, left-to-right top-to-bottom — every position intentional. */
    private static final int[] ITEM_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;
    private final ShopCategory category;
    private final int page;

    public ShopCategoryGui(final CoreMCPlugin plugin, final ShopCategory category) {
        this(plugin, category, 0);
    }

    ShopCategoryGui(final CoreMCPlugin plugin, final ShopCategory category, final int page) {
        this.plugin = plugin;
        this.category = category;
        this.page = Math.max(0, page);
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» " + category.display());
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        // amber backdrop keeps the click-map legible and unfriendly to theft-sweeps
        for (int slot = 0; slot < size(); slot++) {
            inventory.setItem(slot, GuiService.item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        }
        final List<ShopEntry> entries = plugin.shop().entriesOf(category);
        final int pages = Math.max(1, (entries.size() + ITEM_SLOTS.length - 1) / ITEM_SLOTS.length);
        final int safePage = Math.min(page, pages - 1);
        final int offset = safePage * ITEM_SLOTS.length;
        final var profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        for (int i = 0; i < ITEM_SLOTS.length && offset + i < entries.size(); i++) {
            final ShopEntry entry = entries.get(offset + i);
            final List<String> lore = new ArrayList<>();
            lore.add("&7Amount: &fx" + entry.amount());
            lore.add("&7Price: &a" + String.format(Locale.ROOT, "%,d", entry.price())
                    + " &7" + entry.currency().displayName());
            lore.add("");
            final boolean affordable = profile != null && profile.balanceOf(entry.currency()) >= entry.price();
            lore.add(affordable ? "&a✔ Left-click to purchase." : "&c✖ You cannot afford this.");
            if (entry.sellPrice() > 0L) {
                lore.add("&eRight-click to sell: &a" + String.format(Locale.ROOT, "%,d", entry.sellPrice())
                        + " &7" + entry.currency().displayName() + " &7each.");
            }
            inventory.setItem(ITEM_SLOTS[i], GuiService.item(entry.material(), entry.display(), lore));
        }
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&e&lBack", List.of("&7Return to the shop.")));
        if (safePage > 0) {
            inventory.setItem(SLOT_PREVIOUS, GuiService.item(
                    Material.ARROW, "&ePrevious Page", List.of("&7Page " + safePage + " of " + pages)));
        }
        inventory.setItem(SLOT_PAGE, GuiService.item(
                Material.PAPER, "&fPage " + (safePage + 1) + "&7/&f" + pages,
                List.of("&7" + entries.size() + " items in this category.")));
        if (safePage + 1 < pages) {
            inventory.setItem(SLOT_NEXT, GuiService.item(
                    Material.ARROW, "&eNext Page", List.of("&7Page " + (safePage + 2) + " of " + pages)));
        }
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        if (slot == SLOT_BACK) {
            plugin.gui().open(viewer, new ShopMainGui(plugin));
            return false;
        }
        final List<ShopEntry> entries = plugin.shop().entriesOf(category);
        final int pages = Math.max(1, (entries.size() + ITEM_SLOTS.length - 1) / ITEM_SLOTS.length);
        final int safePage = Math.min(page, pages - 1);
        if (slot == SLOT_PREVIOUS && safePage > 0) {
            plugin.gui().open(viewer, new ShopCategoryGui(plugin, category, safePage - 1));
            return false;
        }
        if (slot == SLOT_NEXT && safePage + 1 < pages) {
            plugin.gui().open(viewer, new ShopCategoryGui(plugin, category, safePage + 1));
            return false;
        }
        final int offset = safePage * ITEM_SLOTS.length;
        for (int i = 0; i < ITEM_SLOTS.length; i++) {
            if (slot != ITEM_SLOTS[i]) {
                continue;
            }
            if (offset + i >= entries.size()) {
                return false;
            }
            if (plugin.shop().purchase(viewer, entries.get(offset + i))) {
                return true; // success re-render (balance surfaces elsewhere stay fresh)
            }
            return false; // refused: keep panel, message was sent
        }
        return false;
    }

    @Override
    public boolean onRightClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE || slot == SLOT_BACK || slot == SLOT_PREVIOUS || slot == SLOT_NEXT) {
            return onClick(viewer, slot);
        }
        final List<ShopEntry> entries = plugin.shop().entriesOf(category);
        final int pages = Math.max(1, (entries.size() + ITEM_SLOTS.length - 1) / ITEM_SLOTS.length);
        final int offset = Math.min(page, pages - 1) * ITEM_SLOTS.length;
        for (int i = 0; i < ITEM_SLOTS.length; i++) {
            if (slot != ITEM_SLOTS[i]) {
                continue;
            }
            if (offset + i >= entries.size()) {
                return false;
            }
            return plugin.shop().sell(viewer, entries.get(offset + i));
        }
        return false;
    }
}
