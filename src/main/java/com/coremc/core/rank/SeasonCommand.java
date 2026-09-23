package com.coremc.core.rank;

import com.coremc.core.config.MessageService;
import com.coremc.core.island.IslandTopRewards;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * {@code /season} — shows the current season number;
 * {@code /season set <n>} (admin) starts a new season, paying out
 * every online ranked player immediately (offline players get their
 * payout on their next join) and paying the island top rewards — the
 * leaders of each leaderboard win webstore gift cards.
 */
public final class SeasonCommand implements CommandExecutor, TabCompleter {

    private final RankService ranks;
    private final IslandTopRewards islandTopRewards;
    private final MessageService messages;

    public SeasonCommand(final RankService ranks, final IslandTopRewards islandTopRewards,
                         final MessageService messages) {
        this.ranks = ranks;
        this.islandTopRewards = islandTopRewards;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (ranks == null) {
            messages.sendPrefixed(sender, "rank.unavailable");
            return true;
        }
        if (args.length >= 2 && "set".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("coremc.season.admin")) {
                messages.sendPrefixed(sender, "rank.no-permission");
                return true;
            }
            final int season;
            try {
                season = Integer.parseInt(args[1]);
            } catch (final NumberFormatException exception) {
                messages.sendPrefixed(sender, "rank.season-usage");
                return true;
            }
            if (season < 1) {
                messages.sendPrefixed(sender, "rank.season-usage");
                return true;
            }
            ranks.setSeason(season);
            if (islandTopRewards != null) {
                islandTopRewards.payOut(season);
            }
            messages.sendPrefixed(sender, "rank.season-set", Map.of(
                    "season", String.valueOf(season)));
            return true;
        }
        messages.sendPrefixed(sender, "rank.season-current", Map.of(
                "season", String.valueOf(ranks.season())));
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (args.length == 1 && sender.hasPermission("coremc.season.admin")) {
            return filter(List.of("set"), args[0]);
        }
        return List.of();
    }

    private List<String> filter(final List<String> options, final String prefix) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        final List<String> matches = new java.util.ArrayList<>();
        for (final String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
