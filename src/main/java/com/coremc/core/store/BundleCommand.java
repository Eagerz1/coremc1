package com.coremc.core.store;

import com.coremc.core.config.MessageService;
import com.coremc.core.reward.PendingRewards;
import com.coremc.core.reward.RewardDeliverer;
import com.coremc.core.reward.RewardGrant;
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
 * {@code /corebundle give <player> <type> <amount>} — admin grants of
 * a bundle's exact contents ({@code coremc.admin.bundles}). Delivery
 * runs through the pending ledger, so a full inventory keeps the
 * remainder for {@code /rewards} instead of dropping it. Logged.
 */
public final class BundleCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "coremc.admin.bundles";

    private final BundleConfig config;
    private final PendingRewards pending;
    private final RewardDeliverer deliverer;
    private final TransactionLog transactions;
    private final MessageService messages;

    public BundleCommand(final BundleConfig config, final PendingRewards pending,
                         final RewardDeliverer deliverer, final TransactionLog transactions,
                         final MessageService messages) {
        this.config = config;
        this.pending = pending;
        this.deliverer = deliverer;
        this.transactions = transactions;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (config == null || pending == null || deliverer == null) {
            messages.sendPrefixed(sender, "store.unavailable");
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "store.no-permission");
            return true;
        }
        if (args.length < 4 || !args[0].equalsIgnoreCase("give")) {
            messages.sendPrefixed(sender, "store.bundle-usage");
            return true;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "store.player-not-found", Map.of("player", args[1]));
            return true;
        }
        final BundleDef bundle = config.byId(args[2]);
        if (bundle == null) {
            messages.sendPrefixed(sender, "store.unknown-bundle", Map.of("bundle", args[2]));
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
        final List<RewardGrant> grants = new ArrayList<>();
        for (int index = 0; index < amount; index++) {
            grants.addAll(bundle.expand());
        }
        final String txnId = UUID.randomUUID().toString();
        pending.add(target.getUniqueId(), txnId, "admin-bundle:" + bundle.id(), grants);
        transactions.record(txnId, target.getUniqueId(), "ADMIN_GIVEBUNDLE",
                "by=" + sender.getName() + " what=" + bundle.id() + " amount=" + amount);
        pending.deliver(target.getUniqueId(), grant -> deliverer.deliver(target, grant));
        if (pending.has(target.getUniqueId(), txnId)) {
            messages.sendPrefixed(target, "store.delivery-pending");
        }
        messages.sendPrefixed(sender, "store.bundle-given", Map.of(
                "player", target.getName(), "amount", String.valueOf(amount),
                "bundle", plain(bundle.name())));
        return true;
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
        if (config == null) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("give");
        }
        if (args.length == 3) {
            final List<String> ids = new ArrayList<>();
            for (final BundleDef bundle : config.all()) {
                ids.add(bundle.id());
            }
            return ids.stream().filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return null;
    }
}
