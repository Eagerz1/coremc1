package com.coremc.core.guide;

import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Polished /help GUI, focused on server systems and progression paths. */
public final class HelpGui {

    public static final int SIZE = 54;
    public static final int SLOT_BACK = 45;
    public static final int SLOT_CLOSE = 49;
    public static final int SLOT_SHORTCUT = 53;
    private static final int[] CATEGORY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private final JavaPlugin plugin;
    private final HelpConfig config;

    public HelpGui(final JavaPlugin plugin, final HelpConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void openRoot(final Player player) {
        final HelpMenu menu = new HelpMenu(HelpMenu.Kind.ROOT, "");
        final Inventory inv = Bukkit.createInventory(menu, SIZE, ColorUtil.colorize("&3&lCOREMC &8— &bGuide"));
        menu.inventory(inv);
        int i = 0;
        for (final HelpConfig.Category category : config.categories()) {
            if (i >= CATEGORY_SLOTS.length) {
                break;
            }
            inv.setItem(CATEGORY_SLOTS[i++], categoryItem(category));
        }
        inv.setItem(SLOT_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inv);
        player.openInventory(inv);
    }

    public void openCategory(final Player player, final String id) {
        final HelpConfig.Category category = config.category(id);
        if (category == null) {
            openRoot(player);
            return;
        }
        if ("commands".equals(category.id())) {
            openCommands(player);
            return;
        }
        final HelpMenu menu = new HelpMenu(HelpMenu.Kind.PAGE, category.id());
        final Inventory inv = Bukkit.createInventory(menu, SIZE,
                ColorUtil.colorize("&3&lGuide &8— &b" + category.title()));
        menu.inventory(inv);
        inv.setItem(13, pageItem(category));
        inv.setItem(SLOT_BACK, GuiItems.back());
        inv.setItem(SLOT_CLOSE, GuiItems.close());
        if (shortcutExists(category)) {
            inv.setItem(SLOT_SHORTCUT, shortcutItem(category));
        }
        GuiItems.fillEmpty(inv);
        player.openInventory(inv);
    }

    public void openCommands(final Player player) {
        final HelpMenu menu = new HelpMenu(HelpMenu.Kind.COMMANDS, "commands");
        final Inventory inv = Bukkit.createInventory(menu, SIZE, ColorUtil.colorize("&3&lGuide &8— &bCommands"));
        menu.inventory(inv);
        final Map<String, List<String>> groups = new LinkedHashMap<>();
        for (final HelpConfig.CommandEntry entry : config.commands()) {
            if (!registered(entry.pluginCommand())) {
                continue;
            }
            groups.computeIfAbsent(entry.group(), ignored -> new ArrayList<>())
                    .add("&8• &f" + entry.display() + " &7— " + entry.description());
        }
        int slot = 10;
        for (final Map.Entry<String, List<String>> entry : groups.entrySet()) {
            if (slot > 34) {
                break;
            }
            final List<String> lore = new ArrayList<>(entry.getValue());
            inv.setItem(slot, GuiItems.item(Material.PAPER, "&b&l" + entry.getKey(), lore.toArray(new String[0])));
            slot += slot == 16 ? 3 : 1;
            if (slot == 17) {
                slot = 19;
            } else if (slot == 26) {
                slot = 28;
            }
        }
        inv.setItem(SLOT_BACK, GuiItems.back());
        inv.setItem(SLOT_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inv);
        player.openInventory(inv);
    }

    public String categoryAt(final int slot) {
        int i = 0;
        for (final HelpConfig.Category category : config.categories()) {
            if (i >= CATEGORY_SLOTS.length) {
                break;
            }
            if (CATEGORY_SLOTS[i] == slot) {
                return category.id();
            }
            i++;
        }
        return null;
    }

    public boolean registered(final String command) {
        final String id = HelpConfig.normalise(command);
        if (id.isBlank() || plugin == null) {
            return false;
        }
        final PluginCommand pluginCommand = plugin.getCommand(id);
        return pluginCommand != null;
    }

    public boolean shortcutExists(final HelpConfig.Category category) {
        return category != null && !category.shortcut().isBlank()
                && registered(category.pluginCommand().isBlank() ? firstWord(category.shortcut()) : category.pluginCommand());
    }

    public void runShortcut(final Player player, final HelpConfig.Category category) {
        if (category == null || !shortcutExists(category)) {
            return;
        }
        player.closeInventory();
        player.performCommand(category.shortcut());
    }

    private ItemStack categoryItem(final HelpConfig.Category category) {
        final List<String> lore = new ArrayList<>();
        if (category.lines().isEmpty()) {
            lore.add("&7Open this guide page.");
        } else {
            for (int i = 0; i < Math.min(3, category.lines().size()); i++) {
                lore.add(category.lines().get(i));
            }
        }
        lore.add(" ");
        lore.add("&eClick to open");
        return GuiItems.item(category.icon(), "&b&l" + category.title(), lore.toArray(new String[0]));
    }

    private ItemStack pageItem(final HelpConfig.Category category) {
        final List<String> lore = new ArrayList<>(category.lines());
        if (shortcutExists(category)) {
            lore.add(" ");
            lore.add("&eShortcut: &f/" + category.shortcut());
        }
        return GuiItems.item(category.icon(), "&e&l" + category.title(), lore.toArray(new String[0]));
    }

    private ItemStack shortcutItem(final HelpConfig.Category category) {
        return GuiItems.item(Material.COMPASS, "&e&lOpen /" + category.shortcut(),
                "&7ᴏᴘᴇɴ ᴛʜᴇ ʀᴇʟᴀᴛᴇᴅ ᴄᴏʀᴇᴍᴄ ɢᴜɪ.",
                "&eClick to run command");
    }

    private String firstWord(final String command) {
        final String clean = command == null ? "" : command.trim();
        final int space = clean.indexOf(' ');
        return space < 0 ? clean : clean.substring(0, space);
    }
}
