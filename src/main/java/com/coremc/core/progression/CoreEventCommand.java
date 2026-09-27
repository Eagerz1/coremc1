package com.coremc.core.progression;

import com.coremc.core.config.MessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** Admin command for hourly CoreMC events. */
public final class CoreEventCommand implements CommandExecutor, TabCompleter {

    private final ServerEventConfig config;
    private final ServerEventService events;
    private final MessageService messages;

    public CoreEventCommand(final ServerEventConfig config, final ServerEventService events,
                            final MessageService messages) {
        this.config = config;
        this.events = events;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (events == null || !config.enabled()) {
            messages.sendPrefixed(sender, "event.unavailable");
            return true;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            status(sender);
            return true;
        }
        if (!sender.hasPermission("coremc.event.admin")) {
            messages.sendPrefixed(sender, "event.no-permission");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (args.length < 2) {
                    messages.sendPrefixed(sender, "event.usage");
                    return true;
                }
                if (events.start(args[1], true)) {
                    messages.sendPrefixed(sender, "event.manual-start", Map.of("event", args[1]));
                } else {
                    messages.sendPrefixed(sender, "event.manual-failed");
                }
            }
            case "stop" -> {
                if (events.stop(true)) {
                    messages.sendPrefixed(sender, "event.manual-stop");
                } else {
                    messages.sendPrefixed(sender, "event.none-active");
                }
            }
            case "next" -> messages.sendPrefixed(sender, "event.next", Map.of(
                    "time", formatWhen(events.nextStartAt())));
            default -> messages.sendPrefixed(sender, "event.usage");
        }
        return true;
    }

    private void status(final CommandSender sender) {
        final ServerEventConfig.EventDef current = events.currentEvent();
        if (current == null) {
            messages.sendPrefixed(sender, "event.status-none", Map.of(
                    "next", formatWhen(events.nextStartAt())));
        } else {
            messages.sendPrefixed(sender, "event.status-active", Map.of(
                    "event", current.name(),
                    "remaining", formatDuration(events.remainingMillis())));
        }
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias,
                                      final String[] args) {
        if (args.length == 1) {
            return filter(List.of("start", "stop", "status", "next"), args[0]);
        }
        if (args.length == 2 && "start".equalsIgnoreCase(args[0])) {
            final List<String> ids = new ArrayList<>();
            for (final ServerEventConfig.EventDef event : config.events()) {
                ids.add(event.id());
            }
            return filter(ids, args[1]);
        }
        return List.of();
    }

    private List<String> filter(final List<String> values, final String prefix) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        final List<String> result = new ArrayList<>();
        for (final String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }

    private String formatWhen(final long time) {
        final long remaining = Math.max(0L, time - System.currentTimeMillis());
        return formatDuration(remaining);
    }

    private String formatDuration(final long millis) {
        final long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }
}
