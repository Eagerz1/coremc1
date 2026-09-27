package com.coremc.core.season;

import com.coremc.core.rank.SeasonCommand;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * /journey command with a safe /season alias. Legacy rank /season set <n>
 * is delegated so island-top payouts are not broken while players can use
 * /season as a friendly shortcut to the Journey GUI.
 */
public final class SeasonAliasCommand implements CommandExecutor, TabCompleter {

    private final SeasonJourneyCommand journey;
    private final SeasonCommand rankSeason;

    public SeasonAliasCommand(final SeasonJourneyCommand journey, final SeasonCommand rankSeason) {
        this.journey = journey;
        this.rankSeason = rankSeason;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (rankSeason != null && isRankSeasonCommand(args)) {
            return rankSeason.onCommand(sender, command, label, args);
        }
        return journey.onCommand(sender, command, label, args);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        final List<String> result = new ArrayList<>();
        if (rankSeason != null && args.length == 1 && sender.hasPermission("coremc.season.admin")) {
            result.add("set");
        }
        return result;
    }

    private boolean isRankSeasonCommand(final String[] args) {
        return args.length > 0 && "set".equalsIgnoreCase(args[0]);
    }
}
