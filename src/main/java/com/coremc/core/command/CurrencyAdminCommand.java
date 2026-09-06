package com.coremc.core.command;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.economy.Currency;
import com.coremc.core.economy.EconomyService;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Shared implementation for {@code /credits} and {@code /skytokens}.
 *
 *   /<currency>                          show your own balance (everyone)
 *   /<currency> give|remove|set <player> <amount>   admin (op by default)
 *
 * Offline players are supported: name resolution uses the username
 * index (+ Bukkit usercache fallback); offline profile mutation runs on
 * the profile I/O worker and flushes immediately, so paid currency can
 * never be lost between restarts.
 */
public final class CurrencyAdminCommand implements CommandExecutor, TabCompleter {

    private final CoreMCPlugin plugin;
    private final Currency currency;
    private final EconomyService economy;
    private final PlayerDataService playerData;
    private final MessageService messages;

    public CurrencyAdminCommand(final CoreMCPlugin plugin, final Currency currency) {
        this.plugin = plugin;
        this.currency = currency;
        this.economy = plugin.economy();
        this.playerData = plugin.playerData();
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        final String key = currency.messageKey();
        if (args.length == 0) {
            // own balance
            if (!(sender instanceof Player player)) {
                messages.sendPrefixed(sender, "player-only", Map.of());
                return true;
            }
            playerData.profileOf(player.getUniqueId()).ifPresent(profile -> messages.sendPrefixed(
                    sender,
                    key + ".balance",
                    Map.of(
                            "amount", String.valueOf(economy.balanceOf(profile, currency)),
                            "currency", currency.displayName())));
            return true;
        }

        final String sub = args[0].toLowerCase();
        if (!sub.equals("give") && !sub.equals("remove") && !sub.equals("set")) {
            messages.sendPrefixed(sender, key + ".usage", Map.of("label", label));
            return true;
        }
        if (!sender.hasPermission("coremc.admin." + key)) {
            messages.sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length < 3) {
            messages.sendPrefixed(sender, key + ".usage", Map.of("label", label));
            return true;
        }

        final long amount;
        try {
            amount = economy.parseAmount(args[2]);
            if ((sub.equals("give") || sub.equals("remove")) && amount <= 0L) {
                messages.sendPrefixed(sender, "economy.error.invalid", Map.of());
                return true;
            }
        } catch (final EconomyService.AmountException exception) {
            messages.sendPrefixed(sender, "economy.error." + exception.token(), Map.of());
            return true;
        }

        final String targetName = args[1];
        // Resolve + mutate OFF the main thread: offline players load from disk.
        playerData.ioExecute(() -> {
            final Optional<UUID> targetUuid = playerData.resolveUuid(targetName);
            if (targetUuid.isEmpty()) {
                messages.sendPrefixed(sender, "economy.unknown-player", Map.of("player", targetName));
                return;
            }
            final Optional<PlayerProfile> profile = playerData.cachedOrLoad(targetUuid.get());
            if (profile.isEmpty()) {
                messages.sendPrefixed(sender, "economy.unknown-player", Map.of("player", targetName));
                return;
            }
            mutate(sender, profile.get(), sub, amount, key);
        });
        return true;
    }

    private void mutate(
            final CommandSender sender,
            final PlayerProfile profile,
            final String sub,
            final long amount,
            final String key) {
        final String target = profile.username();
        try {
            switch (sub) {
                case "give" -> {
                    economy.deposit(profile, currency, amount);
                    messages.sendPrefixed(
                            sender,
                            key + ".given",
                            Map.of(
                                    "player", target,
                                    "amount", String.valueOf(amount),
                                    "balance", String.valueOf(profile.balanceOf(currency))));
                }
                case "remove" -> {
                    if (!economy.withdraw(profile, currency, amount)) {
                        messages.sendPrefixed(
                                sender,
                                key + ".insufficient",
                                Map.of("player", target, "balance", String.valueOf(profile.balanceOf(currency))));
                        return;
                    }
                    messages.sendPrefixed(
                            sender,
                            key + ".removed",
                            Map.of(
                                    "player", target,
                                    "amount", String.valueOf(amount),
                                    "balance", String.valueOf(profile.balanceOf(currency))));
                }
                case "set" -> {
                    economy.setBalance(profile, currency, amount);
                    messages.sendPrefixed(
                            sender,
                            key + ".set-done",
                            Map.of("player", target, "balance", String.valueOf(profile.balanceOf(currency))));
                }
                default -> { }
            }
        } catch (final EconomyService.AmountException exception) {
            messages.sendPrefixed(sender, "economy.error." + exception.token(), Map.of());
        } catch (final RuntimeException exception) {
            messages.sendPrefixed(sender, "economy.error.invalid", Map.of());
            plugin.getLogger().log(Level.SEVERE, "Currency command failed", exception);
        }
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            final String partial = args[0].toLowerCase();
            if (sender.hasPermission("coremc.admin." + currency.messageKey())) {
                for (final String sub : List.of("give", "remove", "set")) {
                    if (sub.startsWith(partial)) {
                        completions.add(sub);
                    }
                }
            }
        } else if (args.length == 2 && isAdminSub(sender, args[0])) {
            final String partial = args[1].toLowerCase();
            for (final Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(partial)) {
                    completions.add(online.getName());
                }
            }
        } else if (args.length == 3 && isAdminSub(sender, args[0])) {
            for (final String suggestion : List.of("1", "100", "1000")) {
                if (suggestion.startsWith(args[2])) {
                    completions.add(suggestion);
                }
            }
        }
        return completions;
    }

    private boolean isAdminSub(final CommandSender sender, final String sub) {
        return (sub.equalsIgnoreCase("give") || sub.equalsIgnoreCase("remove") || sub.equalsIgnoreCase("set"))
                && sender.hasPermission("coremc.admin." + currency.messageKey());
    }
}
