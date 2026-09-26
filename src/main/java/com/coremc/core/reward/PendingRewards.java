package com.coremc.core.reward;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * The pending-reward ledger: everything a player has paid for (or won)
 * but not yet received sits here until it is safely delivered —
 * lootbox openings in flight, purchases that did not fit the
 * inventory, rewards won while offline.
 *
 * <p>Rules that make deliveries safe:</p>
 * <ul>
 *   <li>a transaction is recorded (and persisted) <em>before</em>
 *       anything is handed out, so a crash or disconnect can never
 *       lose a paid reward,</li>
 *   <li>transaction ids are unique — adding the same id twice is a
 *       no-op, so retries are idempotent and can never duplicate,</li>
 *   <li>delivery removes each grant only after the deliverer confirms
 *       it, so a full inventory keeps the remainder pending instead of
 *       dropping or destroying it.</li>
 * </ul>
 *
 * <p>Main-thread only, persisted on every mutation.</p>
 */
public final class PendingRewards {

    /** One pending transaction: id, source label and the grants still owed. */
    public static final class PendingTxn {
        private final String txnId;
        private final String source;
        private final long created;
        private final List<RewardGrant> grants;

        public PendingTxn(final String txnId, final String source, final long created,
                          final List<RewardGrant> grants) {
            this.txnId = txnId;
            this.source = source == null ? "" : source;
            this.created = created;
            this.grants = new ArrayList<>(grants);
        }

        public String txnId() {
            return txnId;
        }

        public String source() {
            return source;
        }

        public long created() {
            return created;
        }

        public List<RewardGrant> grants() {
            return Collections.unmodifiableList(grants);
        }
    }

    private final PendingStore store;
    private final Logger logger;
    private final Map<UUID, List<PendingTxn>> pending;

    public PendingRewards(final PendingStore store, final Logger logger) throws IOException {
        this.store = store;
        this.logger = logger;
        this.pending = store.loadAll();
    }

    /** The player's pending transactions (read-only copy). */
    public List<PendingTxn> of(final UUID player) {
        return List.copyOf(pending.getOrDefault(player, List.of()));
    }

    /** Total grants still owed to the player. */
    public int grantCount(final UUID player) {
        int count = 0;
        for (final PendingTxn txn : pending.getOrDefault(player, List.of())) {
            count += txn.grants.size();
        }
        return count;
    }

    /** True when a transaction id already exists for the player. */
    public boolean has(final UUID player, final String txnId) {
        for (final PendingTxn txn : pending.getOrDefault(player, List.of())) {
            if (txn.txnId.equals(txnId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a transaction. Idempotent: an id already on file is left
     * untouched (returns false), so retries can never duplicate rewards.
     */
    public boolean add(final UUID player, final String txnId, final String source,
                       final List<RewardGrant> grants) {
        if (grants == null || grants.isEmpty()) {
            return false;
        }
        if (has(player, txnId)) {
            return false;
        }
        pending.computeIfAbsent(player, unused -> new ArrayList<>())
                .add(new PendingTxn(txnId, source, System.currentTimeMillis(), grants));
        persist();
        return true;
    }

    /**
     * Delivers everything owed to the player through {@code deliverer}.
     * The deliverer returns true when a grant is safely in the player's
     * hands (balance credited / item in inventory) — only then is it
     * removed. Anything refused stays pending for the next attempt.
     *
     * @return how many grants were delivered
     */
    public int deliver(final UUID player, final Predicate<RewardGrant> deliverer) {
        final List<PendingTxn> txns = pending.get(player);
        if (txns == null || txns.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        boolean changed = false;
        final java.util.Iterator<PendingTxn> txnIterator = txns.iterator();
        while (txnIterator.hasNext()) {
            final PendingTxn txn = txnIterator.next();
            final java.util.Iterator<RewardGrant> grantIterator = txn.grants.iterator();
            while (grantIterator.hasNext()) {
                final RewardGrant grant = grantIterator.next();
                boolean accepted = false;
                try {
                    accepted = deliverer.test(grant);
                } catch (final RuntimeException exception) {
                    logger.warning("Pending grant delivery failed for " + player + " ("
                            + grant.serialize() + "): " + exception.getMessage());
                }
                if (accepted) {
                    grantIterator.remove();
                    delivered++;
                    changed = true;
                }
            }
            if (txn.grants.isEmpty()) {
                txnIterator.remove();
                changed = true;
            }
        }
        if (txns.isEmpty()) {
            pending.remove(player);
        }
        if (changed) {
            persist();
        }
        return delivered;
    }

    /** Removes one whole transaction without delivering (admin cleanup only). */
    public boolean remove(final UUID player, final String txnId) {
        final List<PendingTxn> txns = pending.get(player);
        if (txns == null) {
            return false;
        }
        final boolean removed = txns.removeIf(txn -> txn.txnId.equals(txnId));
        if (txns.isEmpty()) {
            pending.remove(player);
        }
        if (removed) {
            persist();
        }
        return removed;
    }

    private void persist() {
        try {
            store.saveAll(pending);
        } catch (final IOException exception) {
            logger.severe("Could not save pending rewards: " + exception.getMessage());
        }
    }

    /** Flushes the store (called from onDisable). */
    public void shutdown() {
        persist();
    }
}
