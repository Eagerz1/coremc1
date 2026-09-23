package com.coremc.core.util;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Small ItemStack builders shared by the menu GUIs (the shop predates
 * this and keeps its own private helpers).
 */
public final class GuiItems {

    private GuiItems() {
    }

    /** A named item with lore lines, all colour-coded. */
    public static ItemStack item(final Material material, final String name, final String... lore) {
        final ItemStack stack = new ItemStack(material);
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            final List<String> lines = new ArrayList<>(lore.length);
            for (final String line : lore) {
                lines.add(ColorUtil.colorize(line));
            }
            meta.setLore(lines);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** A player-head item with lore lines. */
    public static ItemStack head(final OfflinePlayer owner, final String name, final String... lore) {
        final ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        final ItemMeta meta = stack.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            skull.setDisplayName(ColorUtil.colorize(name));
            final List<String> lines = new ArrayList<>(lore.length);
            for (final String line : lore) {
                lines.add(ColorUtil.colorize(line));
            }
            skull.setLore(lines);
            stack.setItemMeta(skull);
        }
        return stack;
    }

    /** The standard close button. */
    public static ItemStack close() {
        return item(Material.BARRIER, "&c&lClose", "&7Close this menu.");
    }

    /** The standard back button. */
    public static ItemStack back() {
        return item(Material.ARROW, "&c&lBack", "&7Return to the island menu.");
    }

    /** Fills every empty slot of the inventory with grey panes. */
    public static void fillEmpty(final org.bukkit.inventory.Inventory inventory) {
        final ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&r");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }
}
