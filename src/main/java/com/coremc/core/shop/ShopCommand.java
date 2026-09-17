package com.coremc.core.shop;

import com.coremc.core.config.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /shop} — opens the shop GUI. Player-only; the catalogue is
 * fixed per server (customise shop.yml), so there are no
 * subcommands.
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
        gui.openRoot(player);
        return true;
    }
}
