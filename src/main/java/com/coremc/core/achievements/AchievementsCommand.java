package com.coremc.core.achievements;

import com.coremc.core.collections.ClaimResult;
import com.coremc.core.config.MessageService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiText;
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
 * {@code /achievements} (aliases {@code /achievement}, {@code /ach}) —
 * the player-facing Achievement command. No arguments opens the GUI.
 */
public final class AchievementsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("menu", "list", "points", "claim", "secrets", "help");

    private final AchievementConfig config;
    private final AchievementService achievements;
    private final AchievementsGui gui;
    private final MessageService messages;

    public AchievementsCommand(final AchievementConfig config,
                               final AchievementService achievements, final AchievementsGui gui,
                               final MessageService messages) {
        this.config = config;
        this.achievements = achievements;
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!config.enabled()) {
            messages.sendPrefixed(sender, "achievements.disabled");
            return true;
        }
        if (args.length == 0) {
            open(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu", "gui" -> open(sender);
            case "help" -> messages.sendList(sender, "achievements.help");
            case "list" -> list(sender);
            case "secrets" -> {
                if (requirePlayer(sender)) {
                    gui.openSecrets((Player) sender, 0);
                }
            }
            case "points" -> {
                if (requirePlayer(sender)) {
                    final Player player = (Player) sender;
                    messages.sendPrefixed(player, "achievements.points", Map.of(
                            "points", String.valueOf(achievements.points(player.getUniqueId())),
                            "max", String.valueOf(config.maxPoints()),
                            "earned", String.valueOf(
                                    achievements.earnedCount(player.getUniqueId())),
                            "total", String.valueOf(config.all().size()),
                            "percent", String.valueOf(achievements.percent(player.getUniqueId()))));
                }
            }
            case "claim" -> {
                if (requirePlayer(sender)) {
                    claim((Player) sender, args);
                }
            }
            default -> messages.sendPrefixed(sender, "achievements.unknown-subcommand");
        }
        return true;
    }

    private void open(final CommandSender sender) {
        if (requirePlayer(sender)) {
            gui.openRoot((Player) sender);
        }
    }

    private void list(final CommandSender sender) {
        messages.sendPrefixed(sender, "achievements.list-header");
        for (final AchievementCategory category : config.categories()) {
            final int total = config.byCategory(category).size();
            final StringBuilder line = new StringBuilder(category.color() + "  "
                    + GuiText.caps(category.display()) + " &8- &f" + total);
            if (sender instanceof Player player) {
                line.append(" &8- &a").append(achievements.earnedIn(player.getUniqueId(), category))
                        .append(" &7").append(GuiText.caps("earned"));
            }
            sender.sendMessage(ColorUtil.colorize(line.toString()));
        }
    }

    private void claim(final Player player, final String[] args) {
        if (args.length < 2) {
            messages.sendPrefixed(player, "achievements.usage-claim");
            return;
        }
        final Achievement achievement = config.byId(args[1]);
        if (achievement == null || achievements.hidden(player.getUniqueId(), achievement)) {
            messages.sendPrefixed(player, "achievements.unknown-achievement",
                    Map.of("id", args[1]));
            return;
        }
        final ClaimResult result = achievements.claim(player.getUniqueId(), achievement.id());
        final Map<String, String> placeholders = Map.of("achievement", achievement.display());
        switch (result) {
            case CLAIMED -> messages.sendPrefixed(player, "achievements.claimed", placeholders);
            case PENDING -> messages.sendPrefixed(player, "achievements.claim-held", placeholders);
            case ALREADY_CLAIMED ->
                    messages.sendPrefixed(player, "achievements.claim-already", placeholders);
            case NOTHING_TO_CLAIM ->
                    messages.sendPrefixed(player, "achievements.claim-nothing", placeholders);
            default -> messages.sendPrefixed(player, "achievements.claim-locked", placeholders);
        }
    }

    private boolean requirePlayer(final CommandSender sender) {
        if (sender instanceof Player) {
            return true;
        }
        messages.sendPrefixed(sender, "achievements.only-players");
        return false;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        if (args.length == 2 && "claim".equalsIgnoreCase(args[0])) {
            final List<String> ids = new ArrayList<>();
            for (final Achievement achievement : config.all()) {
                if (!(sender instanceof Player player)
                        || !achievements.hidden(player.getUniqueId(), achievement)) {
                    ids.add(achievement.id());
                }
            }
            return prefixed(ids, args[1]);
        }
        return List.of();
    }

    private static List<String> prefixed(final List<String> options, final String typed) {
        final String needle = typed.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(option);
            }
        }
        return out;
    }
}
