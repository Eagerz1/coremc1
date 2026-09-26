package com.coremc.core.store;

import com.coremc.core.config.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /store} — opens the CoreMC store GUI (Crate Keys, Lootboxes,
 * Bundles, paid with Credits). When the store failed to load it says
 * so instead of failing silently.
 */
public final class StoreCommand implements CommandExecutor {

    private final StoreGui gui;
    private final MessageService messages;

    public StoreCommand(final StoreGui gui, final MessageService messages) {
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "store.only-players");
            return true;
        }
        if (gui == null) {
            messages.sendPrefixed(sender, "store.unavailable");
            return true;
        }
        gui.openRoot(player);
        return true;
    }
}
