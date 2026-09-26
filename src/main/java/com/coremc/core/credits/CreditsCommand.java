package com.coremc.core.credits;

import com.coremc.core.config.MessageService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /corecredits}:
 *
 * <ul>
 *   <li>{@code /corecredits [balance [player]]} — Credit balances.</li>
 *   <li>{@code /corecredits give|take|set <player> <amount> [reason]} —
 *       admin tools ({@code coremc.admin.credits}); every action is
 *       written to the audit log with its reason (default ADMIN;
 *       QUEST / EVENT / VOTE / SEASONAL for hooks driven by
 *       commands).</li>
 * </ul>
 */
public final class CreditsCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "coremc.admin.credits";

    private final CreditService credits;
    private final MessageService messages;

    public CreditsCommand(final CreditService credits, final MessageService messages) {
        this.credits = credits;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (credits == null) {
            messages.sendPrefixed(sender, "store.unavailable");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("balance")) {
            if (args.length >= 2) {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    messages.sendPrefixed(sender, "store.no-permission");
                    return true;
                }
                final Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    messages.sendPrefixed(sender, "store.player-not-found",
                            Map.of("player", args[1]));
                    return true;
                }
                messages.sendPrefixed(sender, "store.credits-balance-other", Map.of(
                        "player", target.getName(),
                        "balance", CreditService.format(credits.balance(target.getUniqueId()))));
                return true;
            }
            if (!(sender instanceof Player player)) {
                messages.sendPrefixed(sender, "store.only-players");
                return true;
            }
            messages.sendPrefixed(player, "store.credits-balance", Map.of(
                    "balance", CreditService.format(credits.balance(player.getUniqueId()))));
            return true;
        }

        final String action = args[0].toLowerCase(Locale.ROOT);
        if (!action.equals("give") && !action.equals("take") && !action.equals("set")) {
            messages.sendPrefixed(sender, "store.credits-usage");
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "store.no-permission");
            return true;
        }
        if (args.length < 3) {
            messages.sendPrefixed(sender, "store.credits-usage");
            return true;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "store.player-not-found", Map.of("player", args[1]));
            return true;
        }
        final long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (final NumberFormatException exception) {
            messages.sendPrefixed(sender, "store.bad-amount", Map.of("amount", args[2]));
            return true;
        }
        if (amount < 0) {
            messages.sendPrefixed(sender, "store.bad-amount", Map.of("amount", args[2]));
            return true;
        }
        final CreditReason reason = CreditReason.parse(args.length >= 4 ? args[3] : null);
        final String detail = "by " + sender.getName();
        switch (action) {
            case "give" -> credits.add(target.getUniqueId(), amount, reason, detail);
            case "take" -> {
                if (!credits.take(target.getUniqueId(), amount, reason, detail)) {
                    messages.sendPrefixed(sender, "store.credits-too-low", Map.of(
                            "player", target.getName(),
                            "balance", CreditService.format(credits.balance(target.getUniqueId()))));
                    return true;
                }
            }
            default -> credits.set(target.getUniqueId(), amount, reason);
        }
        messages.sendPrefixed(sender, "store.credits-updated", Map.of(
                "player", target.getName(),
                "balance", CreditService.format(credits.balance(target.getUniqueId()))));
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (args.length == 1) {
            return filter(List.of("balance", "give", "take", "set"), args[0]);
        }
        if (args.length == 4) {
            return filter(List.of("admin", "quest", "event", "vote", "seasonal",
                    "island_milestone"), args[3]);
        }
        return null;
    }

    private static List<String> filter(final List<String> options, final String token) {
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT)
                        .startsWith(token.toLowerCase(Locale.ROOT)))
                .toList();
    }
}
