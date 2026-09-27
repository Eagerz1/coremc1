package com.coremc.core.collections;

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
 * {@code /corecollections} — the staff tool: inspect a player's
 * Collections, add or set progress, and reload the config.
 *
 * <p>Every grant is recorded as {@link com.coremc.core.progress.ProgressSource#ADMIN}
 * in the logs, so hand-outs are always visible in an audit.</p>
 */
public final class CoreCollectionsCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "coremc.collections.admin";
    private static final List<String> SUBS = List.of("info", "add", "set", "reload");

    private final CollectionConfig config;
    private final CollectionService collections;
    private final MessageService messages;

    public CoreCollectionsCommand(final CollectionConfig config,
                                  final CollectionService collections,
                                  final MessageService messages) {
        this.config = config;
        this.collections = collections;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            messages.sendPrefixed(sender, "collections.no-permission");
            return true;
        }
        if (args.length == 0) {
            messages.sendList(sender, "collections.admin-help");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> info(sender, args);
            case "add" -> change(sender, args, true);
            case "set" -> change(sender, args, false);
            case "reload" -> reload(sender);
            default -> messages.sendList(sender, "collections.admin-help");
        }
        return true;
    }

    private void info(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            messages.sendPrefixed(sender, "collections.usage-admin-info");
            return;
        }
        final UUID target = resolve(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "collections.player-not-found",
                    Map.of("player", args[1]));
            return;
        }
        messages.sendPrefixed(sender, "collections.admin-info-header", Map.of(
                "player", args[1],
                "percent", String.valueOf(collections.totalPercent(target)),
                "complete", String.valueOf(collections.completedTotal(target)),
                "total", String.valueOf(config.all().size())));
        for (final CollectionCategory category : config.categories()) {
            sender.sendMessage(ColorUtil.colorize(category.color() + "  "
                    + GuiText.caps(category.display()) + " &8- &b"
                    + collections.categoryPercent(target, category) + "% &8("
                    + collections.completedIn(target, category) + "/"
                    + config.byCategory(category).size() + ")"));
        }
    }

    private void change(final CommandSender sender, final String[] args, final boolean add) {
        if (args.length < 4) {
            messages.sendPrefixed(sender, add ? "collections.usage-admin-add"
                    : "collections.usage-admin-set");
            return;
        }
        final UUID target = resolve(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "collections.player-not-found",
                    Map.of("player", args[1]));
            return;
        }
        final CollectionEntry entry = config.byId(args[2]);
        if (entry == null) {
            messages.sendPrefixed(sender, "collections.unknown-collection", Map.of("id", args[2]));
            return;
        }
        final long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (final NumberFormatException badNumber) {
            messages.sendPrefixed(sender, add ? "collections.usage-admin-add"
                    : "collections.usage-admin-set");
            return;
        }
        if (add) {
            collections.add(target, entry.id(), amount);
        } else {
            collections.set(target, entry.id(), amount);
        }
        messages.sendPrefixed(sender, "collections.admin-changed", Map.of(
                "player", args[1],
                "collection", entry.display(),
                "amount", GuiText.number(amount),
                "total", GuiText.number(collections.amount(target, entry.id()))));
    }

    private void reload(final CommandSender sender) {
        try {
            config.load();
            messages.sendPrefixed(sender, "collections.admin-reloaded",
                    Map.of("count", String.valueOf(config.all().size())));
        } catch (final RuntimeException failure) {
            messages.sendPrefixed(sender, "collections.admin-reload-failed",
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
        if (args.length == 3 && !"info".equalsIgnoreCase(args[0])) {
            final List<String> ids = new ArrayList<>();
            for (final CollectionEntry entry : config.all()) {
                ids.add(entry.id());
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
