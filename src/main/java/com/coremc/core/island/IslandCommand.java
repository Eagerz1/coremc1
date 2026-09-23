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
 * Subcommands: menu (default), create, go, leave, delete [confirm],
 * invite <player>, join, kick <player>, top, help. With no arguments the
 * command opens the island menu GUI — but only for island holders.
 * Unknown subcommands get a branded "does not exist" line followed
 * by the help overview.
 */
public final class IslandCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("menu", "create", "go", "invite", "join", "leave", "delete", "kick", "top", "help");

    private final IslandService islands;
    private final MessageService messages;
    private final IslandGui gui;
    private final IsTopGui topGui;

    public IslandCommand(final IslandService islands, final MessageService messages,
                         final IslandGui gui, final IsTopGui topGui) {
        this.islands = islands;
        this.messages = messages;
        this.gui = gui;
        this.topGui = topGui;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        final String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player player)) {
            if ("help".equals(sub)) {
                messages.sendList(sender, "island.help");
            } else {
                messages.sendPrefixed(sender, "island.only-players");
            }
            return true;
        }

        switch (sub) {
            case "menu", "gui" -> openMenu(player);
            case "create" -> islands.create(player);
            case "go", "home", "teleport" -> islands.goHome(player);
            case "leave" -> islands.leave(player);
            case "delete" -> islands.delete(player, args.length > 1 && "confirm".equalsIgnoreCase(args[1]));
            case "invite" -> islands.invite(player, args);
            case "join", "accept" -> islands.join(player);
            case "kick" -> kick(player, args);
            case "top" -> topGui.open(player);
            default -> {
                messages.sendPrefixed(player, "island.unknown-subcommand");
                messages.sendList(player, "island.help");
            }
        }
        return true;
    }

    /** Opens the island menu — the whole point of /is — but only for island holders. */
    private void openMenu(final Player player) {
        if (islands.islandOf(player.getUniqueId()) == null) {
            messages.sendPrefixed(player, "island.no-island");
            messages.sendList(player, "island.help");
            return;
        }
        gui.openIslandMenu(player);
    }

    /** /is kick <player> — owner-only, removes a member (no confirmation needed). */
    private void kick(final Player player, final String[] args) {
        if (args.length < 2) {
            messages.sendPrefixed(player, "island.kick-usage");
            return;
        }
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            messages.sendPrefixed(player, "island.no-island");
            return;
        }
        final java.util.UUID target = islands.memberByName(island, args[1]);
        if (target == null) {
            messages.sendPrefixed(player, "island.kick-not-member", java.util.Map.of("player", args[1]));
            return;
        }
        islands.kick(player, target, args[1]);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias,
                                      final String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            if ("invite".equalsIgnoreCase(args[0])) {
                final List<String> names = new ArrayList<>();
                org.bukkit.Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
                return filter(names, args[1]);
            }
            if ("kick".equalsIgnoreCase(args[0]) && sender instanceof Player player) {
                final Island island = islands.islandOf(player.getUniqueId());
                if (island != null) {
                    final List<String> names = new ArrayList<>();
                    for (final java.util.UUID memberId : island.members()) {
                        final String name = org.bukkit.Bukkit.getOfflinePlayer(memberId).getName();
                        if (name != null) {
                            names.add(name);
                        }
                    }
                    return filter(names, args[1]);
                }
            }
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
