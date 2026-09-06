package com.coremc.core.command;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.util.ColorUtil;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * {@code /coremc} — administration entry point.
 *
 * Subcommands:
 *   info    — version, server and service state (default)
 *   reload  — reload config.yml + messages.yml (coremc.command.coremc.reload)
 *   help    — usage summary
 */
public final class CoreMCCommand implements CommandExecutor, TabCompleter {

    private final CoreMCPlugin plugin;
    private final MessageService messages;
    private final PlayerDataService playerData;

    public CoreMCCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.messages();
        this.playerData = plugin.playerData();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0 || "info".equalsIgnoreCase(args[0])) {
            sendInfo(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!sender.hasPermission("coremc.command.coremc.reload")) {
                    messages.sendPrefixed(sender, "no-permission", Map.of());
                    return true;
                }
                plugin.reloadCoreConfig();
                messages.sendPrefixed(sender, "reload-success", Map.of());
                return true;
            }
            case "help" -> {
                sendHelp(sender, label);
                return true;
            }
            default -> {
                messages.sendPrefixed(
                        sender, "unknown-subcommand", Map.of("usage", "/" + label + " [info|reload|help]"));
                return true;
            }
        }
    }

    private void sendInfo(final CommandSender sender) {
        final long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        messages.sendList(
                sender,
                "info.lines",
                MessageService.placeholders(
                        "version", plugin.getDescription().getVersion(),
                        "server", plugin.getServer().getName() + " " + plugin.getServer().getVersion(),
                        "profiles", String.valueOf(playerData.cachedProfileCount()),
                        "tasks", String.valueOf(plugin.tasks().trackedTaskCount()),
                        "uptime", formatUptime(uptimeMillis)));
    }

    private void sendHelp(final CommandSender sender, final String label) {
        sender.sendMessage(ColorUtil.colorize("&b&lCOREMC &8— &7commands"));
        sender.sendMessage(ColorUtil.colorize("&f/" + label + " info &8— &7Show plugin and server state"));
        sender.sendMessage(ColorUtil.colorize("&f/" + label + " reload &8— &7Reload configuration"));
        sender.sendMessage(ColorUtil.colorize("&f/profile &8— &7View your player profile"));
        sender.sendMessage(ColorUtil.colorize("&f/heal [player] &8— &7Restore health and hunger"));
        sender.sendMessage(ColorUtil.colorize("&f/is &8— &7Skyblock islands (GUI + team commands)"));
        sender.sendMessage(ColorUtil.colorize("&f/credits | /skytokens &8— &7Balances (admins: give/remove/set)"));
    }

    private static String formatUptime(final long millis) {
        final long seconds = millis / 1000L;
        final long days = seconds / 86_400L;
        final long hours = (seconds % 86_400L) / 3_600L;
        final long minutes = (seconds % 3_600L) / 60L;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m " + (seconds % 60L) + "s";
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            final String partial = args[0].toLowerCase();
            for (final String sub : List.of("info", "reload", "help")) {
                if ("reload".equals(sub) && !sender.hasPermission("coremc.command.coremc.reload")) {
                    continue;
                }
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
        }
        return completions;
    }
}
