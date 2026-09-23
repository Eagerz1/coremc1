package com.coremc.core.rank;

import com.coremc.core.config.MessageService;
import com.coremc.core.shop.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /rank} (alias {@code /ranks}) — the rank ladder command:
 * view your rank and perks, list the ladder, buy the next rank, and
 * the admin tool to set or clear a player's rank.
 */
public final class RankCommand implements CommandExecutor, TabCompleter {

    private final RankConfig config;
    private final RankService ranks;
    private final MessageService messages;

    public RankCommand(final RankConfig config, final RankService ranks,
                       final MessageService messages) {
        this.config = config;
        this.ranks = ranks;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (args.length == 0 || "view".equalsIgnoreCase(args[0])) {
            view(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "buy" -> {
                if (ranks == null) {
                    messages.sendPrefixed(sender, "rank.unavailable");
                } else if (requirePlayer(sender)) {
                    ranks.buyNext((Player) sender);
                }
            }
            case "set" -> set(sender, args);
            default -> messages.sendPrefixed(sender, "rank.unknown-subcommand");
        }
        return true;
    }

    private void view(final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            list(sender);
            return;
        }
        if (!config.enabled() || ranks == null) {
            messages.sendPrefixed(sender, "rank.unavailable");
            return;
        }
        final RankConfig.RankDef rank = ranks.rankOf(player.getUniqueId());
        if (rank == null) {
            final RankConfig.RankDef first = config.first();
            messages.sendPrefixed(player, "rank.no-rank", Map.of(
                    "rank", first.name(),
                    "cost", Money.format(first.price(), "$")));
            return;
        }
        final RankConfig.RankDef next = config.nextOf(rank);
        messages.sendList(player, "rank.view", Map.of(
                "rank", rank.name(),
                "multiplier", "x" + trim(rank.moneyMultiplier()),
                "season-money", Money.format(rank.seasonMoney(), "$"),
                "keys", String.valueOf(ranks.riverKeys(player.getUniqueId())),
                "perks", rank.perksSummary(),
                "next", next == null ? "" : next.name(),
                "next-cost", next == null ? "" : Money.format(next.price(), "$"),
                "season", String.valueOf(ranks.season())));
    }

    private void list(final CommandSender sender) {
        if (!config.enabled() || ranks == null) {
            messages.sendPrefixed(sender, "rank.unavailable");
            return;
        }
        final List<String> lines = new ArrayList<>();
        lines.add(messages.get("rank.list-header"));
        for (final RankConfig.RankDef rank : config.ranks()) {
            lines.add(messages.get("rank.list-entry", Map.of(
                    "rank", rank.name(),
                    "cost", Money.format(rank.price(), "$"),
                    "multiplier", "x" + trim(rank.moneyMultiplier()),
                    "season-money", Money.format(rank.seasonMoney(), "$"),
                    "keys", String.valueOf(rank.riverKeys()),
                    "perks", rank.perksSummary())));
        }
        lines.add(messages.get("rank.list-footer"));
        for (final String line : lines) {
            sender.sendMessage(line);
        }
    }

    private void set(final CommandSender sender, final String[] args) {
        if (ranks == null) {
            messages.sendPrefixed(sender, "rank.unavailable");
            return;
        }
        if (!sender.hasPermission("coremc.rank.admin")) {
            messages.sendPrefixed(sender, "rank.no-permission");
            return;
        }
        if (args.length < 3) {
            messages.sendPrefixed(sender, "rank.set-usage");
            return;
        }
        final Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "rank.player-not-online", Map.of("player", args[1]));
            return;
        }
        if ("none".equalsIgnoreCase(args[2])) {
            ranks.setRank(target, "none");
            messages.sendPrefixed(sender, "rank.set-none", Map.of("player", target.getName()));
            messages.sendPrefixed(target, "rank.rank-removed");
            return;
        }
        final RankConfig.RankDef rank = config.byId(args[2]);
        if (rank == null) {
            messages.sendPrefixed(sender, "rank.not-a-rank", Map.of("rank", args[2]));
            return;
        }
        ranks.setRank(target, rank.id());
        messages.sendPrefixed(sender, "rank.set-done", Map.of(
                "player", target.getName(), "rank", rank.name()));
    }

    private boolean requirePlayer(final CommandSender sender) {
        if (sender instanceof Player) {
            return true;
        }
        messages.sendPrefixed(sender, "rank.only-players");
        return false;
    }

    private static String trim(final double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (args.length == 1) {
            final List<String> subs = new ArrayList<>(List.of("view", "list", "buy"));
            if (sender.hasPermission("coremc.rank.admin")) {
                subs.add("set");
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2 && "set".equalsIgnoreCase(args[0])
                && sender.hasPermission("coremc.rank.admin")) {
            final List<String> names = new ArrayList<>();
            org.bukkit.Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return filter(names, args[1]);
        }
        if (args.length == 3 && "set".equalsIgnoreCase(args[0])
                && sender.hasPermission("coremc.rank.admin")) {
            final List<String> ids = new ArrayList<>();
            config.ranks().forEach(rank -> ids.add(rank.id()));
            ids.add("none");
            return filter(ids, args[2]);
        }
        return List.of();
    }

    private List<String> filter(final List<String> options, final String prefix) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>();
        for (final String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
