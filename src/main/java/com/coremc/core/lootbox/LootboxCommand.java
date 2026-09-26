package com.coremc.core.lootbox;

import com.coremc.core.config.MessageService;
import com.coremc.core.store.TransactionLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /corelootbox give <player> <type> <amount>} — admin grants of
 * physical PDC lootboxes ({@code coremc.admin.lootboxes}); every grant
 * is written to the transaction log.
 */
public final class LootboxCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "coremc.admin.lootboxes";

    private final LootboxConfig config;
    private final LootboxItems items;
    private final TransactionLog transactions;
    private final MessageService messages;

    public LootboxCommand(final LootboxConfig config, final LootboxItems items,
                          final TransactionLog transactions, final MessageService messages) {
        this.config = config;
        this.items = items;
        this.transactions = transactions;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (config == null || !config.enabled() || items == null) {
            messages.sendPrefixed(sender, "store.unavailable");
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "store.no-permission");
            return true;
        }
        if (args.length < 4 || !args[0].equalsIgnoreCase("give")) {
            messages.sendPrefixed(sender, "store.lootbox-usage");
            return true;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "store.player-not-found", Map.of("player", args[1]));
            return true;
        }
        final LootboxDef box = config.byId(args[2]);
        if (box == null) {
            messages.sendPrefixed(sender, "store.lootbox-unknown");
            return true;
        }
        final int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (final NumberFormatException exception) {
            messages.sendPrefixed(sender, "store.bad-amount", Map.of("amount", args[3]));
            return true;
        }
        if (amount <= 0) {
            messages.sendPrefixed(sender, "store.bad-amount", Map.of("amount", args[3]));
            return true;
        }
        int remaining = amount;
        while (remaining > 0) {
            final int size = Math.min(64, remaining);
            final var leftover = target.getInventory().addItem(items.build(box, size));
            if (!leftover.isEmpty()) {
                int given = amount - remaining;
                for (final var stack : leftover.values()) {
                    given += size - stack.getAmount();
                }
                messages.sendPrefixed(sender, "store.inventory-full-partial", Map.of(
                        "given", String.valueOf(given), "wanted", String.valueOf(amount)));
                log(sender, target, box, given);
                return true;
            }
            remaining -= size;
        }
        messages.sendPrefixed(sender, "store.lootbox-given", Map.of(
                "player", target.getName(), "amount", String.valueOf(amount),
                "box", plain(box.name())));
        log(sender, target, box, amount);
        return true;
    }

    private void log(final CommandSender sender, final Player target, final LootboxDef box,
                     final int amount) {
        transactions.record(UUID.randomUUID().toString(), target.getUniqueId(),
                "ADMIN_GIVELOOTBOX", "by=" + sender.getName() + " what=" + box.id()
                        + " amount=" + amount);
    }

    private static String plain(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '&' && index + 1 < text.length()) {
                index++;
                continue;
            }
            out.append(text.charAt(index));
        }
        return out.toString();
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (config == null || !config.enabled()) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("give");
        }
        if (args.length == 3) {
            final List<String> ids = new ArrayList<>();
            for (final LootboxDef box : config.all()) {
                ids.add(box.id());
            }
            return ids.stream().filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return null;
    }
}
