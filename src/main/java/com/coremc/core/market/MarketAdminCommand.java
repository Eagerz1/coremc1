package com.coremc.core.market;

import com.coremc.core.config.MessageService;
import com.coremc.core.store.TransactionLog;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /coremarket} — staff controls (permission
 * {@code coremc.admin.market}, every action audit-logged):
 * {@code status | open | close | rotate | auction start | auction
 * stop}. Manual operations cooperate with the automatic schedule
 * (a manually closed window stays closed; a manual session refuses
 * to overlap a running one). There is deliberately NO wipe/reset.
 */
public final class MarketAdminCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "coremc.admin.market";

    private final MarketService market;
    private final DarkAuctionService auction;
    private final TransactionLog transactions;
    private final MessageService messages;
    private final java.util.function.LongSupplier clock;

    public MarketAdminCommand(final MarketService market, final DarkAuctionService auction,
                              final TransactionLog transactions, final MessageService messages,
                              final java.util.function.LongSupplier clock) {
        this.market = market;
        this.auction = auction;
        this.transactions = transactions;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            messages.sendPrefixed(sender, "store.no-permission");
            return true;
        }
        if (market == null || !market.config().enabled()) {
            messages.sendPrefixed(sender, "market.unavailable");
            return true;
        }
        final String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> {
                messages.sendPrefixed(sender, "market.admin-status", Map.of(
                        "status", market.statusLine(clock.getAsLong()),
                        "auction", auction.active()
                                ? "session " + auction.sessionId() : "no session"));
                return true;
            }
            case "open" -> {
                final boolean opened = market.adminOpen();
                audit(sender, "open", opened);
                messages.sendPrefixed(sender,
                        opened ? "market.admin-opened" : "market.admin-already-open");
                return true;
            }
            case "close" -> {
                final boolean closed = market.adminClose();
                audit(sender, "close", closed);
                messages.sendPrefixed(sender,
                        closed ? "market.admin-closed" : "market.admin-already-closed");
                return true;
            }
            case "rotate" -> {
                final boolean rotated = market.adminRotate();
                audit(sender, "rotate", rotated);
                messages.sendPrefixed(sender,
                        rotated ? "market.admin-rotated" : "market.admin-already-closed");
                return true;
            }
            case "auction" -> {
                final String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (sub.equals("start")) {
                    final boolean started = auction.start(null, "admin " + sender.getName());
                    audit(sender, "auction-start", started);
                    messages.sendPrefixed(sender, started
                            ? "market.admin-auction-started" : "market.admin-auction-busy");
                    return true;
                }
                if (sub.equals("stop")) {
                    final boolean wasActive = auction.active();
                    if (wasActive) {
                        auction.end("admin stop by " + sender.getName());
                    }
                    audit(sender, "auction-stop", wasActive);
                    messages.sendPrefixed(sender, wasActive
                            ? "market.admin-auction-stopped" : "market.admin-no-auction");
                    return true;
                }
                messages.sendPrefixed(sender, "market.admin-usage");
                return true;
            }
            default -> {
                messages.sendPrefixed(sender, "market.admin-usage");
                return true;
            }
        }
    }

    private void audit(final CommandSender sender, final String action, final boolean applied) {
        final UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        transactions.record(UUID.randomUUID().toString(), actor, "ADMIN_MARKET",
                "action=" + action + " by=" + sender.getName() + " applied=" + applied);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (args.length == 1) {
            return List.of("status", "open", "close", "rotate", "auction");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("auction")) {
            return List.of("start", "stop");
        }
        return List.of();
    }
}
