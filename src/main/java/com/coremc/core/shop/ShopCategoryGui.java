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
 * 28..34 / 37..43 (4×7 = 28 max items), navigation at 45 (back) and 53 (close).
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
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;
    private final ShopCategory category;

    public ShopCategoryGui(final CoreMCPlugin plugin, final ShopCategory category) {
        this.plugin = plugin;
        this.category = category;
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
        for (int i = 0; i < ITEM_SLOTS.length && i < entries.size(); i++) {
            final ShopEntry entry = entries.get(i);
            final List<String> lore = new ArrayList<>();
            lore.add("&7Amount: &fx" + entry.amount());
            lore.add("&7Price: &a" + String.format(Locale.ROOT, "%,d", entry.price())
                    + " &7" + entry.currency().displayName());
            lore.add("");
            lore.add("&eClick to purchase.");
            if (entry.sellPrice() > 0L) {
                lore.add("&eRight-click to sell: &a" + String.format(Locale.ROOT, "%,d", entry.sellPrice())
                        + " &7" + entry.currency().displayName() + " &7each.");
            }
            inventory.setItem(ITEM_SLOTS[i], GuiService.item(entry.material(), entry.display(), lore));
        }
        inventory.setItem(SLOT_BACK, GuiService.item(Material.ARROW, "&e&lBack", List.of("&7Return to the shop.")));
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
        for (int i = 0; i < ITEM_SLOTS.length; i++) {
            if (slot != ITEM_SLOTS[i]) {
                continue;
            }
            final List<ShopEntry> entries = plugin.shop().entriesOf(category);
            if (i >= entries.size()) {
                return false;
            }
            if (plugin.shop().purchase(viewer, entries.get(i))) {
                return true; // success re-render (balance surfaces elsewhere stay fresh)
            }
            return false; // refused: keep panel, message was sent
        }
        return false;
    }

    @Override
    public boolean onRightClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE || slot == SLOT_BACK) {
            return onClick(viewer, slot);
        }
        for (int i = 0; i < ITEM_SLOTS.length; i++) {
            if (slot != ITEM_SLOTS[i]) {
                continue;
            }
            final List<ShopEntry> entries = plugin.shop().entriesOf(category);
            if (i >= entries.size()) {
                return false;
            }
            return plugin.shop().sell(viewer, entries.get(i));
        }
        return false;
    }
}
