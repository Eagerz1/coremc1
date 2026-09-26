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
 *
 * <p>Everything here speaks the CoreMC GUI design language: coloured
 * item names, short small-caps lore ({@link GuiText}), blank lines as
 * separators and consistent back / close buttons.</p>
 */
public final class GuiItems {

    private GuiItems() {
    }

    /** A named item with lore lines, all colour-coded. */
    public static ItemStack item(final Material material, final String name, final String... lore) {
        return item(material, name, List.of(lore));
    }

    /** A named item with lore lines, all colour-coded. */
    public static ItemStack item(final Material material, final String name, final List<String> lore) {
        final ItemStack stack = new ItemStack(material);
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            final List<String> lines = new ArrayList<>(lore.size());
            for (final String line : lore) {
                lines.add(ColorUtil.colorize(line));
            }
            meta.setLore(lines);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** The same item with the enchantment glint (used for "you can buy this"). */
    public static ItemStack glow(final ItemStack stack) {
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** A player-head item with lore lines. */
    public static ItemStack head(final OfflinePlayer owner, final String name, final String... lore) {
        return head(owner, name, List.of(lore));
    }

    /** A player-head item with lore lines. */
    public static ItemStack head(final OfflinePlayer owner, final String name, final List<String> lore) {
        final ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        final ItemMeta meta = stack.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            skull.setDisplayName(ColorUtil.colorize(name));
            final List<String> lines = new ArrayList<>(lore.size());
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
        return item(Material.BARRIER, "&c&l" + GuiText.caps("Close"),
                "&7" + GuiText.caps("Close this menu."));
    }

    /** The standard back button (returns to the island menu). */
    public static ItemStack back() {
        return back("island menu");
    }

    /** The standard back button, naming where it goes. */
    public static ItemStack back(final String target) {
        return item(Material.ARROW, "&e&l" + GuiText.caps("Back"),
                "&7" + GuiText.caps("Return to the " + target + "."));
    }

    /** The standard menu filler pane. */
    public static ItemStack filler() {
        return item(Material.GRAY_STAINED_GLASS_PANE, "&r");
    }

    /** Fills every empty slot of the inventory with grey panes. */
    public static void fillEmpty(final org.bukkit.inventory.Inventory inventory) {
        final ItemStack filler = filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    /** Fills the given slots with a border pane (frames a menu). */
    public static void frame(final org.bukkit.inventory.Inventory inventory, final int... slots) {
        final ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, "&r");
        for (final int slot : slots) {
            if (slot >= 0 && slot < inventory.getSize() && inventory.getItem(slot) == null) {
                inventory.setItem(slot, pane);
            }
        }
    }
}
