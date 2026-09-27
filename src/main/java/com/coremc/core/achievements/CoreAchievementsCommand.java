package com.coremc.core.achievements;

import com.coremc.core.config.MessageService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * {@code /coreachievements} (alias {@code /coreachievement}) — the
 * staff tool: inspect a player's Achievements, grant one by hand, and
 * reload the config.
 *
 * <p>There is deliberately no "revoke": Achievements are a permanent
 * record, and quietly removing one would make the whole system
 * untrustworthy. A mis-granted Achievement is fixed by editing the
 * data file with the server stopped.</p>
 */
public final class CoreAchievementsCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "coremc.achievements.admin";
    private static final List<String> SUBS = List.of("info", "grant", "reload");

    private final AchievementConfig config;
    private final AchievementService achievements;
    private final MessageService messages;

    public CoreAchievementsCommand(final AchievementConfig config,
                                   final AchievementService achievements,
                                   final MessageService messages) {
        this.config = config;
        this.achievements = achievements;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            messages.sendPrefixed(sender, "achievements.no-permission");
            return true;
        }
        if (args.length == 0) {
            messages.sendList(sender, "achievements.admin-help");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> info(sender, args);
            case "grant" -> grant(sender, args);
            case "reload" -> reload(sender);
            default -> messages.sendList(sender, "achievements.admin-help");
        }
        return true;
    }

    private void info(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            messages.sendPrefixed(sender, "achievements.usage-admin-info");
            return;
        }
        final UUID target = resolve(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "achievements.player-not-found",
                    Map.of("player", args[1]));
            return;
        }
        messages.sendPrefixed(sender, "achievements.admin-info-header", Map.of(
                "player", args[1],
                "points", String.valueOf(achievements.points(target)),
                "max", String.valueOf(config.maxPoints()),
                "earned", String.valueOf(achievements.earnedCount(target)),
                "total", String.valueOf(config.all().size())));
        for (final AchievementCategory category : config.categories()) {
            sender.sendMessage(ColorUtil.colorize(category.color() + "  "
                    + GuiText.caps(category.display()) + " &8- &a"
                    + achievements.earnedIn(target, category) + "&7/&f"
                    + config.byCategory(category).size()));
        }
    }

    private void grant(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            messages.sendPrefixed(sender, "achievements.usage-admin-grant");
            return;
        }
        final UUID target = resolve(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "achievements.player-not-found",
                    Map.of("player", args[1]));
            return;
        }
        final Achievement achievement = config.byId(args[2]);
        if (achievement == null) {
            messages.sendPrefixed(sender, "achievements.unknown-achievement",
                    Map.of("id", args[2]));
            return;
        }
        final boolean granted = achievements.grant(target, achievement.id());
        messages.sendPrefixed(sender, granted ? "achievements.admin-granted"
                : "achievements.admin-already", Map.of(
                "player", args[1], "achievement", achievement.display()));
    }

    private void reload(final CommandSender sender) {
        try {
            config.load();
            messages.sendPrefixed(sender, "achievements.admin-reloaded",
                    Map.of("count", String.valueOf(config.all().size())));
        } catch (final RuntimeException failure) {
            messages.sendPrefixed(sender, "achievements.admin-reload-failed",
                    Map.of("error", String.valueOf(failure.getMessage())));
        }
    }

    private static UUID resolve(final String name) {
        final var online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        final OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        if (args.length == 2) {
            final List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return prefixed(names, args[1]);
        }
        if (args.length == 3 && "grant".equalsIgnoreCase(args[0])) {
            final List<String> ids = new ArrayList<>();
            for (final Achievement achievement : config.all()) {
                ids.add(achievement.id());
            }
            return prefixed(ids, args[2]);
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
