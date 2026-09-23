package com.coremc.core.shop;

import com.coremc.core.config.MessageService;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /shop} — opens the shop GUI. Player-only; the catalogue is
 * fixed per server (customise shop.yml).
 *
 * <ul>
 *   <li>{@code /shop} — the root menu.</li>
 *   <li>{@code /shop <section>} — jump straight into a section (by id
 *       or display name); grouped sections open their picker.</li>
 *   <li>{@code /shop <section> <group>} — jump straight into a
 *       subcategory of a grouped section.</li>
 * </ul>
 */
public final class ShopCommand implements CommandExecutor {

    private final ShopConfig config;
    private final ShopGui gui;
    private final MessageService messages;

    public ShopCommand(final ShopConfig config, final ShopGui gui, final MessageService messages) {
        this.config = config;
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "shop.only-players");
            return true;
        }
        if (config.sections().isEmpty()) {
            messages.sendPrefixed(player, "shop.disabled");
            return true;
        }
        if (args.length == 0) {
            gui.openRoot(player);
            return true;
        }
        final ShopSection section = findSection(args[0]);
        if (section == null) {
            messages.sendPrefixed(player, "shop.unknown-section", Map.of("section", args[0]));
            return true;
        }
        if (args.length == 1) {
            gui.openSection(player, section, 0);
            return true;
        }
        final ShopGroup group = findGroup(section, args[1]);
        if (group == null) {
            messages.sendPrefixed(player, "shop.unknown-group",
                    Map.of("section", section.name(), "group", args[1]));
            return true;
        }
        gui.openGroup(player, section, group, 0);
        return true;
    }

    private ShopSection findSection(final String key) {
        ShopSection byId = config.section(key);
        if (byId != null) {
            return byId;
        }
        for (final ShopSection section : config.sections()) {
            if (section.name().equalsIgnoreCase(key)) {
                return section;
            }
        }
        return null;
    }

    private ShopGroup findGroup(final ShopSection section, final String key) {
        if (!section.grouped()) {
            return null;
        }
        ShopGroup byId = section.group(key);
        if (byId != null) {
            return byId;
        }
        for (final ShopGroup group : section.groups()) {
            if (group.name().equalsIgnoreCase(key)) {
                return group;
            }
        }
        return null;
    }
}
