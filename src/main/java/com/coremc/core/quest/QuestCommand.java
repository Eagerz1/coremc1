package com.coremc.core.quest;

import com.coremc.core.config.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /quests player entry point. */
public final class QuestCommand implements CommandExecutor {

    private final QuestService quests;
    private final QuestGui gui;
    private final MessageService messages;

    public QuestCommand(final QuestService quests, final QuestGui gui, final MessageService messages) {
        this.quests = quests;
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
        if (quests == null || !quests.config().enabled()) {
            messages.sendPrefixed(player, "quest.unavailable");
            return true;
        }
        if (args.length > 0) {
            switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
                case "daily" -> gui.openDaily(player);
                case "weekly" -> gui.openWeekly(player);
                case "island", "challenges" -> gui.openIsland(player);
                case "claimable", "completed" -> gui.openCompleted(player);
                default -> gui.openRoot(player);
            }
        } else {
            gui.openRoot(player);
        }
        return true;
    }
}
