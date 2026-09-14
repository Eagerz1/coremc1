package com.coremc.core.island;

import com.coremc.core.config.MessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * /is — the island command. A thin dispatcher: all gameplay logic lives
 * in {@link IslandService}.
 *
 * Subcommands: create, go, leave, delete [confirm], invite <player>,
 * join, help.
 */
public final class IslandCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("create", "go", "invite", "join", "leave", "delete", "help");

    private final IslandService islands;
    private final MessageService messages;

    public IslandCommand(final IslandService islands, final MessageService messages) {
        this.islands = islands;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        final String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player player)) {
            if ("help".equals(sub)) {
                messages.sendList(sender, "island.help");
            } else {
                messages.sendPrefixed(sender, "island.only-players");
            }
            return true;
        }

        switch (sub) {
            case "create" -> islands.create(player);
            case "go", "home", "teleport" -> islands.goHome(player);
            case "leave" -> islands.leave(player);
            case "delete" -> islands.delete(player, args.length > 1 && "confirm".equalsIgnoreCase(args[1]));
            case "invite" -> islands.invite(player, args);
            case "join", "accept" -> islands.join(player);
            default -> messages.sendList(player, "island.help");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias,
                                      final String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && "invite".equalsIgnoreCase(args[0])) {
            final List<String> names = new ArrayList<>();
            org.bukkit.Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return filter(names, args[1]);
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
