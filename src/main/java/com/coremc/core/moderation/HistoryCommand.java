package com.coremc.core.moderation;

import com.coremc.core.CoreMCPlugin;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** /history <player> [page]. */
public final class HistoryCommand implements CommandExecutor, TabCompleter {

    private final ModerationService moderation;

    public HistoryCommand(final CoreMCPlugin plugin) {
        this.moderation = plugin.moderation();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length < 1 || args.length > 2) {
            moderation.prefixed(sender, "&cUsage: /history <player> [page]");
            return true;
        }
        int page = 1;
        if (args.length == 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (final NumberFormatException exception) {
                moderation.prefixed(sender, "&cPage must be a number.");
                return true;
            }
        }
        moderation.executeHistory(sender, args[0], page);
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!moderation.hasCapability(sender, "history")) {
            return List.of();
        }
        if (args.length == 1) {
            return moderation.tabOnlinePlayers(args[0]);
        }
        return List.of();
    }
}
