package com.coremc.core.market;

import com.coremc.core.config.MessageService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /blackmarket} (aliases {@code /bm}, {@code /darkauction}):
 *
 * <ul>
 *   <li>no args — the market board (open offers, or the closed
 *       status board); called as {@code /darkauction} it opens the
 *       auction floor instead,</li>
 *   <li>{@code auction} — the auction floor,</li>
 *   <li>{@code bid <amount>} — a custom Money bid on the live
 *       lot.</li>
 * </ul>
 */
public final class MarketCommand implements CommandExecutor, TabCompleter {

    private final MarketGui gui;
    private final MarketService market;
    private final DarkAuctionService auction;
    private final MessageService messages;
    private final java.util.function.LongSupplier clock;

    public MarketCommand(final MarketGui gui, final MarketService market,
                         final DarkAuctionService auction, final MessageService messages,
                         final java.util.function.LongSupplier clock) {
        this.gui = gui;
        this.market = market;
        this.auction = auction;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "store.only-players");
            return true;
        }
        if (gui == null || market == null || !market.config().enabled()) {
            messages.sendPrefixed(sender, "market.unavailable");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("bid")) {
            if (args.length < 2) {
                messages.sendPrefixed(player, "market.bid-usage");
                return true;
            }
            final long amount = parseAmount(args[1]);
            if (amount <= 0) {
                messages.sendPrefixed(player, "store.bad-amount", Map.of("amount", args[1]));
                return true;
            }
            auction.bid(player, amount);
            return true;
        }
        final boolean wantsAuction = label.equalsIgnoreCase("darkauction")
                || (args.length >= 1 && args[0].equalsIgnoreCase("auction"));
        if (wantsAuction) {
            gui.openAuction(player, clock.getAsLong());
        } else {
            gui.open(player, clock.getAsLong());
        }
        return true;
    }

    /** Accepts 250000, 250k and 1.5m. */
    static long parseAmount(final String raw) {
        try {
            final String cleaned = raw.trim().toLowerCase(Locale.ROOT).replace(",", "");
            if (cleaned.endsWith("k")) {
                return (long) (Double.parseDouble(cleaned.substring(0,
                        cleaned.length() - 1)) * 1_000);
            }
            if (cleaned.endsWith("m")) {
                return (long) (Double.parseDouble(cleaned.substring(0,
                        cleaned.length() - 1)) * 1_000_000);
            }
            return (long) Double.parseDouble(cleaned);
        } catch (final NumberFormatException exception) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (args.length == 1) {
            return List.of("auction", "bid");
        }
        return List.of();
    }
}
