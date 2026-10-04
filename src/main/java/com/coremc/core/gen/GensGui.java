package com.coremc.core.gen;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gui.Gui;
import com.coremc.core.gui.GuiService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** {@code /gens} — paginated generator market. */
public final class GensGui implements Gui {

    private static final int[] ITEM_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int SLOT_BALANCE = 45;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_CLOSE = 53;

    private final CoreMCPlugin plugin;
    private final int page;

    public GensGui(final CoreMCPlugin plugin) {
        this(plugin, 0);
    }

    GensGui(final CoreMCPlugin plugin, final int page) {
        this.plugin = plugin;
        this.page = Math.max(0, page);
    }

    @Override
    public String title() {
        return ColorUtil.colorize("&b&lCOREMC &8» &fGenerator Market");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(final Player viewer, final Inventory inventory) {
        final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
        final List<GeneratorDefinition> gens = plugin.generators().all();
        final int pages = Math.max(1, (gens.size() + ITEM_SLOTS.length - 1) / ITEM_SLOTS.length);
        final int safePage = Math.min(page, pages - 1);
        final int offset = safePage * ITEM_SLOTS.length;
        for (int i = 0; i < ITEM_SLOTS.length && offset + i < gens.size(); i++) {
            final GeneratorDefinition def = gens.get(offset + i);
            final String price = String.format(Locale.ROOT, "%,d", def.priceCredits());
            final boolean affordable = profile != null && profile.credits() >= def.priceCredits();
            inventory.setItem(ITEM_SLOTS[i], GuiService.item(
                    def.blockMaterial(), (affordable ? "&a✔ " : "&c✖ ") + def.display(),
                    List.of(
                            "&7Produces: &f" + def.productName(),
                            "&7Cooldown: &f" + def.cooldownSeconds() + "s",
                            "", "&7Price: &a" + price + " Credits",
                            affordable ? "&a✔ Click to purchase." : "&c✖ You cannot afford this.")));
        }
        final long balance = profile == null ? 0L : profile.credits();
        inventory.setItem(SLOT_BALANCE, GuiService.item(Material.GOLD_INGOT, "&6Your Credits",
                List.of("&7Balance: &a" + String.format(Locale.ROOT, "%,d", balance))));
        if (safePage > 0) {
            inventory.setItem(SLOT_PREVIOUS, GuiService.item(Material.ARROW, "&ePrevious Page",
                    List.of("&7Page " + safePage + " of " + pages)));
        }
        inventory.setItem(SLOT_PAGE, GuiService.item(Material.PAPER,
                "&fPage " + (safePage + 1) + "&7/&f" + pages,
                List.of("&7" + gens.size() + " generators available.")));
        if (safePage + 1 < pages) {
            inventory.setItem(SLOT_NEXT, GuiService.item(Material.ARROW, "&eNext Page",
                    List.of("&7Page " + (safePage + 2) + " of " + pages)));
        }
        inventory.setItem(SLOT_CLOSE, GuiService.item(Material.BARRIER, "&c&lClose", List.of()));
        GuiService.fillGaps(inventory);
    }

    @Override
    public boolean onClick(final Player viewer, final int slot) {
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return false;
        }
        final List<GeneratorDefinition> gens = plugin.generators().all();
        final int pages = Math.max(1, (gens.size() + ITEM_SLOTS.length - 1) / ITEM_SLOTS.length);
        final int safePage = Math.min(page, pages - 1);
        if (slot == SLOT_PREVIOUS && safePage > 0) {
            plugin.gui().open(viewer, new GensGui(plugin, safePage - 1));
            return false;
        }
        if (slot == SLOT_NEXT && safePage + 1 < pages) {
            plugin.gui().open(viewer, new GensGui(plugin, safePage + 1));
            return false;
        }
        final int offset = safePage * ITEM_SLOTS.length;
        for (int i = 0; i < ITEM_SLOTS.length; i++) {
            if (slot != ITEM_SLOTS[i] || offset + i >= gens.size()) {
                continue;
            }
            final PlayerProfile profile = plugin.playerData().profileOf(viewer.getUniqueId()).orElse(null);
            if (profile == null) {
                return false;
            }
            plugin.generators().buy(viewer, profile, gens.get(offset + i));
            return true;
        }
        return false;
    }
}
