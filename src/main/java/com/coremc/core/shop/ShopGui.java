package com.coremc.core.shop;

import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Renders the shop GUIs (root: small chest; sections: double chest
 * pages). All state lives in
 * the {@link ShopMenu} holder, windows are rebuilt on every
 * navigation, and the click controller (see ShopListener) decides
 * what a click means — this class only draws.
 */
public final class ShopGui {

    private final ShopConfig config;
    private final EconomyService economy;

    public ShopGui(final ShopConfig config, final EconomyService economy) {
        this.config = config;
        this.economy = economy;
    }

    /** Opens the shop root menu (section overview + balance). */
    public void openRoot(final Player player) {
        final ShopMenu menu = ShopMenu.root();
        final Inventory inventory = Bukkit.createInventory(menu, ShopLayout.SIZE,
                ColorUtil.colorize("&3&lCOREMC &8— &bShop"));
        menu.inventory(inventory);

        inventory.setItem(ShopLayout.ROOT_BALANCE_SLOT, balanceItem(player));
        final List<ShopSection> sections = config.sections();
        for (int i = 0; i < sections.size(); i++) {
            final ShopSection section = sections.get(i);
            inventory.setItem(ShopLayout.sectionSlot(i), sectionIcon(section));
        }
        inventory.setItem(ShopLayout.ROOT_CLOSE_SLOT, closeItem());
        fillEmpty(inventory);

        player.openInventory(inventory);
    }

    /**
     * Opens a section for browsing. Flat sections show their paginated
     * item list directly; grouped sections show the subcategory picker
     * (each group then opens its own paginated pages).
     */
    public void openSection(final Player player, final ShopSection section, final int page) {
        if (section.grouped()) {
            openPicker(player, section);
            return;
        }
        openItemPage(player, ShopMenu.section(section.id(), page),
                "&3&lShop &8— &b" + section.name(),
                section.items(), "this section");
    }

    /** Opens a grouped section's subcategory picker. */
    public void openPicker(final Player player, final ShopSection section) {
        final ShopMenu menu = ShopMenu.picker(section.id());
        final Inventory inventory = Bukkit.createInventory(menu, ShopLayout.SECTION_SIZE,
                ColorUtil.colorize("&3&lShop &8— &b" + section.name()));
        menu.inventory(inventory);

        final List<ShopGroup> groups = section.groups();
        for (int i = 0; i < groups.size(); i++) {
            inventory.setItem(ShopLayout.groupSlot(i), groupIcon(groups.get(i)));
        }

        inventory.setItem(ShopLayout.SLOT_BACK,
                navItem(Material.ARROW, "&c&lBack", "&7Return to the shop menu."));
        inventory.setItem(ShopLayout.SLOT_CLOSE, closeItem());
        fillEmpty(inventory);

        player.openInventory(inventory);
    }

    /** Opens one page of a subcategory of a grouped section. */
    public void openGroup(final Player player, final ShopSection section,
                          final ShopGroup group, int page) {
        openItemPage(player, ShopMenu.group(section.id(), group.id(), page),
                "&3&lShop &8— &b" + section.name() + " &8· &b" + group.name(),
                group.items(), group.name());
    }

    /** Renders a paginated item window (used by flat sections and groups alike). */
    private void openItemPage(final Player player, final ShopMenu menu, final String title,
                              final List<ShopItem> items, final String context) {
        final int pages = ShopLayout.pageCount(items.size());
        final int page = Math.max(0, Math.min(menu.page(), pages - 1));
        final Inventory inventory = Bukkit.createInventory(menu, ShopLayout.SECTION_SIZE,
                ColorUtil.colorize(title + " &7(" + (page + 1) + "/" + pages + ")"));
        menu.inventory(inventory);

        final List<ShopItem> pageItems = ShopLayout.pageItems(items, page);
        for (int i = 0; i < pageItems.size(); i++) {
            inventory.setItem(i, shopItem(pageItems.get(i)));
        }

        final String backLore = menu.kind() == ShopMenu.Kind.GROUP
                ? "&7Return to the categories." : "&7Return to the shop menu.";
        inventory.setItem(ShopLayout.SLOT_BACK, navItem(Material.ARROW, "&c&lBack", backLore));
        if (page > 0) {
            inventory.setItem(ShopLayout.SLOT_PREVIOUS,
                    navItem(Material.ARROW, "&e&lPrevious page", "&7Go to page " + page + "/" + pages + "."));
        }
        inventory.setItem(ShopLayout.SLOT_PAGE,
                navItem(Material.BOOK, "&fPage " + (page + 1) + " &7of " + pages,
                        "&7" + items.size() + " items in " + context + "."));
        if (page < pages - 1) {
            inventory.setItem(ShopLayout.SLOT_NEXT,
                    navItem(Material.ARROW, "&e&lNext page", "&7Go to page " + (page + 2) + "/" + pages + "."));
        }
        inventory.setItem(ShopLayout.SLOT_CLOSE, closeItem());
        fillEmpty(inventory);

        player.openInventory(inventory);
    }

    /** Balance display for the root menu. */
    private ItemStack balanceItem(final Player player) {
        final ItemStack stack = new ItemStack(Material.GOLD_INGOT);
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize("&6&lYour balance"));
            meta.setLore(List.of(ColorUtil.colorize(
                    "&e" + Money.format(economy.balance(player.getUniqueId()), config.currencySymbol()))));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack sectionIcon(final ShopSection section) {
        final ItemStack stack = new ItemStack(section.icon());
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            final String hint = section.grouped()
                    ? "&7" + section.groups().size() + " categories, " + section.itemCount() + " items"
                    : "&7" + section.itemCount() + " items";
            meta.setDisplayName(ColorUtil.colorize("&b&l" + section.name()));
            meta.setLore(List.of(ColorUtil.colorize(hint + " &8— &eclick to browse")));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack groupIcon(final ShopGroup group) {
        final ItemStack stack = new ItemStack(group.icon());
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize("&b&l" + group.name()));
            meta.setLore(List.of(ColorUtil.colorize(
                    "&7" + group.itemCount() + " items &8— &eclick to browse")));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack shopItem(final ShopItem item) {
        final ItemStack stack = new ItemStack(item.material());
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            final List<String> lore = new ArrayList<>();
            lore.add(ColorUtil.colorize("&7Buy:  &a" + Money.format(item.buyPrice(), config.currencySymbol())));
            lore.add(ColorUtil.colorize("&7Sell: &e" + Money.format(item.sellPrice(), config.currencySymbol())));
            lore.add("");
            if (item.buyable()) {
                lore.add(ColorUtil.colorize("&eLeft-click &7— buy &f1"));
                lore.add(ColorUtil.colorize("&eShift+Left &7— buy &f16"));
            }
            if (item.sellable()) {
                lore.add(ColorUtil.colorize("&eRight-click &7— sell &f1"));
                lore.add(ColorUtil.colorize("&eShift+Right &7— sell &fall"));
            }
            meta.setDisplayName(ColorUtil.colorize("&f" + item.displayName()));
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack navItem(final Material material, final String name, final String... lore) {
        final ItemStack stack = new ItemStack(material);
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            final List<String> loreLines = new ArrayList<>(lore.length);
            for (final String line : lore) {
                loreLines.add(ColorUtil.colorize(line));
            }
            meta.setDisplayName(ColorUtil.colorize(name));
            meta.setLore(loreLines);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack closeItem() {
        return navItem(Material.BARRIER, "&c&lClose", "&7Close the shop.");
    }

    private void fillEmpty(final Inventory inventory) {
        final ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        final ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize("&r"));
            filler.setItemMeta(meta);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }
}
