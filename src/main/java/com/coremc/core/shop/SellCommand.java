package com.coremc.core.shop;

import com.coremc.core.config.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /sell} — opens the sell window: a double chest you drop items
 * into; closing it pays you for everything sellable. Player-only and
 * needs the economy to be up (same failure mode as /shop).
 */
public final class SellCommand implements CommandExecutor {

    private final EconomyService economy;
    private final SellGui gui;
    private final MessageService messages;

    public SellCommand(final EconomyService economy, final SellGui gui,
                       final MessageService messages) {
        this.economy = economy;
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
        if (economy == null) {
            messages.sendPrefixed(player, "shop.disabled");
            return true;
        }
        gui.open(player);
        return true;
    }
}
