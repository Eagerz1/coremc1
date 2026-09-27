package com.coremc.core.guide;

import com.coremc.core.config.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /help opens the CoreMC guide GUI instead of a command dump. */
public final class HelpCommand implements CommandExecutor {

    private final HelpConfig config;
    private final HelpGui gui;
    private final MessageService messages;

    public HelpCommand(final HelpConfig config, final HelpGui gui, final MessageService messages) {
        this.config = config;
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "island.only-players");
            return true;
        }
        if (config == null || !config.enabled()) {
            messages.sendPrefixed(player, "guide.unavailable");
            return true;
        }
        if (args.length > 0) {
            gui.openCategory(player, args[0]);
        } else {
            gui.openRoot(player);
        }
        return true;
    }
}
