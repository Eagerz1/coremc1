package com.coremc.core.season;

import com.coremc.core.config.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /journey player command. */
public final class SeasonJourneyCommand implements CommandExecutor {

    private final SeasonJourneyService journey;
    private final SeasonJourneyGui gui;
    private final MessageService messages;

    public SeasonJourneyCommand(final SeasonJourneyService journey, final SeasonJourneyGui gui,
                                final MessageService messages) {
        this.journey = journey;
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
        if (journey == null || !journey.config().enabled()) {
            messages.sendPrefixed(player, "journey.unavailable");
            return true;
        }
        final int page;
        if (args.length > 0) {
            try {
                page = Math.max(0, Integer.parseInt(args[0]) - 1);
            } catch (final NumberFormatException ignored) {
                gui.open(player, 0);
                return true;
            }
        } else {
            page = 0;
        }
        gui.open(player, page);
        return true;
    }
}
