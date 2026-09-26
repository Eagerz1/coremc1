package com.coremc.core.crate;

import com.coremc.core.config.MessageService;
import com.coremc.core.store.TransactionLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /corecrate} — crate administration
 * ({@code coremc.admin.crates}):
 *
 * <ul>
 *   <li>{@code givekey <player> <type> <amount>} — grants physical
 *       PDC keys (logged),</li>
 *   <li>{@code set <crate>} — binds the block you look at,
 *       {@code set <crate> <world> <x> <y> <z>} from the console,</li>
 *   <li>{@code remove} / {@code remove <world> <x> <y> <z>} —
 *       unbinds,</li>
 *   <li>{@code list} — every binding.</li>
 * </ul>
 */
public final class CrateCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "coremc.admin.crates";

    private final CrateConfig config;
    private final CrateService crates;
    private final KeyItems keyItems;
    private final TransactionLog transactions;
    private final MessageService messages;

    public CrateCommand(final CrateConfig config, final CrateService crates,
                        final KeyItems keyItems, final TransactionLog transactions,
                        final MessageService messages) {
        this.config = config;
        this.crates = crates;
        this.keyItems = keyItems;
        this.transactions = transactions;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (crates == null || config == null || !config.enabled()) {
            messages.sendPrefixed(sender, "store.unavailable");
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "store.no-permission");
            return true;
        }
        if (args.length == 0) {
            messages.sendPrefixed(sender, "store.crate-usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "givekey" -> giveKey(sender, args);
            case "set" -> bind(sender, args);
            case "remove" -> unbind(sender, args);
            case "list" -> list(sender);
            default -> messages.sendPrefixed(sender, "store.crate-usage");
        }
        return true;
    }

    private void giveKey(final CommandSender sender, final String[] args) {
        if (args.length < 4) {
            messages.sendPrefixed(sender, "store.crate-usage");
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "store.player-not-found", Map.of("player", args[1]));
            return;
        }
        final KeyDef key = config.key(args[2]);
        if (key == null) {
            messages.sendPrefixed(sender, "store.unknown-key", Map.of("key", args[2]));
            return;
        }
        final int amount = parseAmount(args[3]);
        if (amount <= 0) {
            messages.sendPrefixed(sender, "store.bad-amount", Map.of("amount", args[3]));
            return;
        }
        int remaining = amount;
        while (remaining > 0) {
            final int size = Math.min(64, remaining);
            final var leftover = target.getInventory().addItem(keyItems.build(key, size));
            if (!leftover.isEmpty()) {
                // never drop admin-granted keys either: stop and report
                int given = amount - remaining;
                for (final var stack : leftover.values()) {
                    given += size - stack.getAmount();
                }
                messages.sendPrefixed(sender, "store.inventory-full-partial", Map.of(
                        "given", String.valueOf(given), "wanted", String.valueOf(amount)));
                logAdmin(sender, target, "GIVEKEY", key.id(), given);
                return;
            }
            remaining -= size;
        }
        messages.sendPrefixed(sender, "store.key-given", Map.of(
                "player", target.getName(), "amount", String.valueOf(amount),
                "key", plain(key.name())));
        logAdmin(sender, target, "GIVEKEY", key.id(), amount);
    }

    private void bind(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            messages.sendPrefixed(sender, "store.crate-usage");
            return;
        }
        final CrateDef crate = config.crate(args[1]);
        if (crate == null) {
            messages.sendPrefixed(sender, "store.unknown-crate", Map.of("crate", args[1]));
            return;
        }
        if (args.length >= 6) {
            // console form: set <crate> <world> <x> <y> <z>
            try {
                crates.bind(args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                        Integer.parseInt(args[5]), crate);
            } catch (final NumberFormatException exception) {
                messages.sendPrefixed(sender, "store.crate-usage");
                return;
            }
            messages.sendPrefixed(sender, "store.crate-bound", Map.of("crate", plain(crate.name())));
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "store.crate-usage");
            return;
        }
        final Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType().isAir()) {
            messages.sendPrefixed(sender, "store.crate-no-block");
            return;
        }
        crates.bind(target.getWorld().getName(), target.getX(), target.getY(), target.getZ(),
                crate);
        messages.sendPrefixed(sender, "store.crate-bound", Map.of("crate", plain(crate.name())));
    }

    private void unbind(final CommandSender sender, final String[] args) {
        if (args.length >= 5) {
            try {
                final boolean removed = crates.unbind(args[1], Integer.parseInt(args[2]),
                        Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                messages.sendPrefixed(sender,
                        removed ? "store.crate-unbound" : "store.crate-no-block");
            } catch (final NumberFormatException exception) {
                messages.sendPrefixed(sender, "store.crate-usage");
            }
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "store.crate-usage");
            return;
        }
        final Block target = player.getTargetBlockExact(6);
        if (target == null) {
            messages.sendPrefixed(sender, "store.crate-no-block");
            return;
        }
        final boolean removed = crates.unbind(target.getWorld().getName(), target.getX(),
                target.getY(), target.getZ());
        messages.sendPrefixed(sender, removed ? "store.crate-unbound" : "store.crate-no-block");
    }

    private void list(final CommandSender sender) {
        final Map<String, String> bindings = crates.bindings();
        messages.sendPrefixed(sender, "store.crate-list-header", Map.of(
                "count", String.valueOf(bindings.size())));
        for (final Map.Entry<String, String> entry : bindings.entrySet()) {
            sender.sendMessage(com.coremc.core.util.ColorUtil.colorize(
                    "&7- &f" + entry.getValue() + " &7@ &f" + entry.getKey().replace(';', ' ')));
        }
    }

    private void logAdmin(final CommandSender sender, final Player target, final String action,
                          final String what, final int amount) {
        transactions.record(UUID.randomUUID().toString(), target.getUniqueId(),
                "ADMIN_" + action, "by=" + sender.getName() + " what=" + what
                        + " amount=" + amount);
    }

    private static int parseAmount(final String text) {
        try {
            return Integer.parseInt(text);
        } catch (final NumberFormatException exception) {
            return -1;
        }
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
            return filter(List.of("givekey", "set", "remove", "list"), args[0]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("givekey")) {
            final List<String> keys = new ArrayList<>();
            for (final KeyDef key : config.keys()) {
                keys.add(key.id());
            }
            return filter(keys, args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            final List<String> ids = new ArrayList<>();
            for (final CrateDef crate : config.crates()) {
                ids.add(crate.id());
            }
            return filter(ids, args[1]);
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
