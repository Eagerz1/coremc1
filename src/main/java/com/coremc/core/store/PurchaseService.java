package com.coremc.core.store;

import com.coremc.core.config.MessageService;
import com.coremc.core.credits.CreditReason;
import com.coremc.core.credits.CreditService;
import com.coremc.core.reward.PendingRewards;
import com.coremc.core.reward.RewardDeliverer;
import com.coremc.core.reward.RewardGrant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * The one purchase flow every store buy goes through:
 *
 * <ol>
 *   <li>validate Credits (a shortfall reports exactly how many
 *       Credits are missing, with a failure sound — never a silent
 *       fail),</li>
 *   <li>record the owed grants as a pending transaction (persisted
 *       first, so a crash after payment can never lose the goods),</li>
 *   <li>debit exactly once,</li>
 *   <li>deliver — whatever does not fit the inventory STAYS pending
 *       for {@code /rewards}, it is never dropped,</li>
 *   <li>append the transaction record,</li>
 *   <li>confirm with message + sound.</li>
 * </ol>
 *
 * <p>A per-player in-flight guard plus unique transaction ids make
 * rapid double-clicks unable to double-charge or duplicate.</p>
 */
public final class PurchaseService {

    private final CreditService credits;
    private final PendingRewards pending;
    private final RewardDeliverer deliverer;
    private final TransactionLog transactions;
    private final MessageService messages;
    private final Set<UUID> inFlight = new HashSet<>();

    public PurchaseService(final CreditService credits, final PendingRewards pending,
                           final RewardDeliverer deliverer, final TransactionLog transactions,
                           final MessageService messages) {
        this.credits = credits;
        this.pending = pending;
        this.deliverer = deliverer;
        this.transactions = transactions;
        this.messages = messages;
    }

    /** True when the player can afford {@code price} right now. */
    public boolean affordable(final Player player, final long price) {
        return credits.has(player.getUniqueId(), price);
    }

    /**
     * Runs one purchase. Returns true when the player was charged and
     * the goods are delivered (or safely pending).
     */
    public boolean purchase(final Player player, final long price, final String description,
                            final List<RewardGrant> grants) {
        final UUID playerId = player.getUniqueId();
        if (grants == null || grants.isEmpty() || price < 0) {
            return false;
        }
        if (!inFlight.add(playerId)) {
            return false; // a purchase is already being processed this instant
        }
        try {
            // 1. validate credits — insufficient balance reports the exact shortfall
            if (!credits.has(playerId, price)) {
                final long missing = credits.missing(playerId, price);
                messages.sendPrefixed(player, "store.not-enough-credits", Map.of(
                        "missing", CreditService.format(missing),
                        "price", CreditService.format(price),
                        "balance", CreditService.format(credits.balance(playerId))));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
                return false;
            }

            // 2. record what is owed FIRST (persisted) — a crash between here
            //    and delivery can never lose paid goods, and the unique txn id
            //    makes any retry a no-op
            final String txnId = UUID.randomUUID().toString();
            pending.add(playerId, txnId, "purchase:" + description, grants);

            // 3. debit exactly once
            if (!credits.take(playerId, price, CreditReason.PURCHASE, description)) {
                // raced impossible on the main thread, but stay safe: undo the txn
                pending.remove(playerId, txnId);
                final long missing = credits.missing(playerId, price);
                messages.sendPrefixed(player, "store.not-enough-credits", Map.of(
                        "missing", CreditService.format(missing),
                        "price", CreditService.format(price),
                        "balance", CreditService.format(credits.balance(playerId))));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
                return false;
            }

            // 4. deliver what fits; the rest stays pending for /rewards
            pending.deliver(playerId, grant -> deliverer.deliver(player, grant));
            final boolean allDelivered = !pending.has(playerId, txnId);

            // 5. the transaction record
            transactions.record(txnId, playerId, "PURCHASE",
                    "item='" + description + "' price=" + price
                            + " delivered=" + (allDelivered ? "full" : "partial-pending"));

            // 6. confirmation + sound
            messages.sendPrefixed(player, "store.purchased", Map.of(
                    "item", description,
                    "price", CreditService.format(price),
                    "balance", CreditService.format(credits.balance(playerId))));
            if (!allDelivered) {
                messages.sendPrefixed(player, "store.delivery-pending");
            }
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            return true;
        } finally {
            inFlight.remove(playerId);
        }
    }
}
