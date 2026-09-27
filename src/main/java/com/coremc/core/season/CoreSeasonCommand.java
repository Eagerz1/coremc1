package com.coremc.core.season;

import com.coremc.core.config.MessageService;
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

/** Staff tools for safe Season Journey inspection and XP grants. */
public final class CoreSeasonCommand implements CommandExecutor, TabCompleter {

    private final SeasonJourneyService journey;
    private final java.util.function.Supplier<SeasonConfig> reloadConfig;
    private final MessageService messages;
    private final Logger logger;

    public CoreSeasonCommand(final SeasonJourneyService journey, final java.util.function.Supplier<SeasonConfig> reloadConfig,
                             final MessageService messages, final Logger logger) {
        this.journey = journey;
        this.reloadConfig = reloadConfig;
        this.messages = messages;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!sender.hasPermission("coremc.seasonjourney.admin")) {
            messages.sendPrefixed(sender, "no-permission");
            return true;
        }
        if (journey == null || !journey.config().enabled()) {
            messages.sendPrefixed(sender, "journey.unavailable");
            return true;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            messages.sendPrefixed(sender, "coreseason.status", Map.of(
                    "season", journey.season().name(),
                    "id", journey.season().id(),
                    "remaining", formatDuration(journey.remainingMillis())));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "xp" -> {
                if (args.length < 3) { messages.sendPrefixed(sender, "coreseason.usage"); return true; }
                final OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                final long amount;
                try { amount = Long.parseLong(args[2]); } catch (final NumberFormatException ex) {
                    messages.sendPrefixed(sender, "coreseason.usage"); return true;
                }
                journey.addXp(target.getUniqueId(), amount, SeasonXpSource.ADMIN,
                        "admin:" + sender.getName() + ":" + System.currentTimeMillis());
                audit(sender, "xp " + args[1] + " " + amount);
                messages.sendPrefixed(sender, "coreseason.xp", Map.of("player", args[1], "amount", String.valueOf(amount)));
            }
            case "level" -> {
                if (args.length < 2) { messages.sendPrefixed(sender, "coreseason.usage"); return true; }
                final UUID id = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
                messages.sendPrefixed(sender, "coreseason.level", Map.of("player", args[1],
                        "level", String.valueOf(journey.level(id)), "xp", String.valueOf(journey.xp(id))));
            }
            case "reload" -> {
                try {
                    final SeasonConfig parsed = reloadConfig.get();
                    journey.reload(parsed);
                    journey.transitionAllKnownProfiles();
                    audit(sender, "reload season.yml");
                    messages.sendPrefixed(sender, "coreseason.reloaded");
                } catch (final RuntimeException exception) {
                    messages.sendPrefixed(sender, "coreseason.reload-failed", Map.of("error", exception.getMessage()));
                }
            }
            default -> messages.sendPrefixed(sender, "coreseason.usage");
        }
        return true;
    }

    private void audit(final CommandSender sender, final String action) {
        logger.info("[SeasonJourneyAdmin] " + sender.getName() + " -> " + action);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (!sender.hasPermission("coremc.seasonjourney.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("status", "xp", "level", "reload"), args[0]);
        }
        if (args.length == 2 && ("xp".equalsIgnoreCase(args[0]) || "level".equalsIgnoreCase(args[0]))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    private List<String> filter(final List<String> options, final String prefix) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private String formatDuration(final long millis) {
        final long minutes = Math.max(0L, millis / 60_000L);
        final long days = minutes / (24L * 60L);
        final long hours = (minutes / 60L) % 24L;
        return days + "d " + hours + "h";
    }
}
