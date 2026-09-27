package com.coremc.core.quest;

import com.coremc.core.config.MessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** Staff-only quest debug/admin command. */
public final class CoreQuestCommand implements CommandExecutor, TabCompleter {

    private final QuestService quests;
    private final MessageService messages;
    private final Logger logger;

    public CoreQuestCommand(final QuestService quests, final MessageService messages, final Logger logger) {
        this.quests = quests;
        this.messages = messages;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!sender.hasPermission("coremc.quest.admin")) {
            messages.sendPrefixed(sender, "no-permission");
            return true;
        }
        if (quests == null || !quests.config().enabled()) {
            messages.sendPrefixed(sender, "quest.unavailable");
            return true;
        }
        if (args.length < 2) {
            messages.sendPrefixed(sender, "corequest.usage");
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        final OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        final UUID id = target.getUniqueId();
        switch (sub) {
            case "reset" -> {
                if (args.length < 3) {
                    messages.sendPrefixed(sender, "corequest.usage");
                    return true;
                }
                final QuestConfig.Scope scope = "weekly".equalsIgnoreCase(args[2])
                        ? QuestConfig.Scope.WEEKLY : QuestConfig.Scope.DAILY;
                quests.reset(id, scope);
                audit(sender, "reset " + target.getName() + " " + scope.name().toLowerCase(Locale.ROOT));
                messages.sendPrefixed(sender, "corequest.reset", Map.of("player", args[1],
                        "scope", scope.name().toLowerCase(Locale.ROOT)));
            }
            case "complete" -> {
                if (args.length < 3) {
                    messages.sendPrefixed(sender, "corequest.usage");
                    return true;
                }
                final boolean ok = quests.complete(id, args[2]);
                audit(sender, "complete " + target.getName() + " " + args[2] + " => " + ok);
                messages.sendPrefixed(sender, ok ? "corequest.completed" : "corequest.missing",
                        Map.of("player", args[1], "quest", args[2]));
            }
            case "reroll" -> {
                quests.reroll(id);
                audit(sender, "reroll " + target.getName());
                messages.sendPrefixed(sender, "corequest.rerolled", Map.of("player", args[1]));
            }
            case "status" -> messages.sendPrefixed(sender, "corequest.status",
                    Map.of("player", args[1], "status", quests.status(id)));
            default -> messages.sendPrefixed(sender, "corequest.usage");
        }
        return true;
    }

    private void audit(final CommandSender sender, final String action) {
        logger.info("[QuestAdmin] " + sender.getName() + " -> " + action);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (!sender.hasPermission("coremc.quest.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("reset", "complete", "reroll", "status"), args[0]);
        }
        if (args.length == 2) {
            final List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return filter(names, args[1]);
        }
        if (args.length == 3 && "reset".equalsIgnoreCase(args[0])) {
            return filter(List.of("daily", "weekly"), args[2]);
        }
        if (args.length == 3 && "complete".equalsIgnoreCase(args[0]) && quests != null) {
            return filter(quests.config().allTemplates().stream().map(QuestConfig.QuestTemplate::id).toList(), args[2]);
        }
        return List.of();
    }

    private List<String> filter(final List<String> options, final String prefix) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
