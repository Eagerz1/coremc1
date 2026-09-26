package com.coremc.core.credits;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Credits — CoreMC's store currency (100 Credits = €1).
 *
 * <p>A clean, whole-number balance service usable by every other CoreMC
 * system: {@code CreditService.add(uuid, amount, CreditReason.QUEST)}.
 * Balances are never negative, every mutation carries a
 * {@link CreditReason} and is written to the audit log, and every
 * mutation is persisted immediately (the file is tiny, durability beats
 * batching — the same policy as coins and essence).</p>
 *
 * <p>All access happens on the main thread.</p>
 */
public final class CreditService {

    /** How many Credits one Euro buys (display constant: 100 ᴄʀᴇᴅɪᴛs = €1). */
    public static final long CREDITS_PER_EURO = 100L;

    private final BalanceStore store;
    private final Logger logger;
    private final Map<UUID, Long> balances;

    public CreditService(final BalanceStore store, final Logger logger) throws IOException {
        this.store = store;
        this.logger = logger;
        this.balances = store.loadAll();
    }

    /** Current Credit balance (0 for unknown players). */
    public long balance(final UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    /** True when the player holds at least {@code amount} Credits. */
    public boolean has(final UUID player, final long amount) {
        return balance(player) >= amount;
    }

    /** How many Credits the player is missing for {@code amount} (0 when affordable). */
    public long missing(final UUID player, final long amount) {
        return Math.max(0L, amount - balance(player));
    }

    /** Adds Credits with a reason (amounts ≤ 0 are ignored). */
    public void add(final UUID player, final long amount, final CreditReason reason) {
        add(player, amount, reason, "");
    }

    /** Adds Credits with a reason and a free-form audit detail. */
    public void add(final UUID player, final long amount, final CreditReason reason,
                    final String detail) {
        if (amount <= 0) {
            return;
        }
        balances.merge(player, amount, Long::sum);
        persist();
        audit("+" + amount, player, reason, detail);
    }

    /**
     * Takes Credits. Returns false (and changes nothing) when the balance
     * is insufficient — Credit balances can never go negative.
     */
    public boolean take(final UUID player, final long amount, final CreditReason reason) {
        return take(player, amount, reason, "");
    }

    /** Takes Credits with a free-form audit detail. */
    public boolean take(final UUID player, final long amount, final CreditReason reason,
                        final String detail) {
        if (amount < 0) {
            return false;
        }
        if (amount == 0) {
            return true;
        }
        final long held = balance(player);
        if (held < amount) {
            return false;
        }
        balances.put(player, held - amount);
        persist();
        audit("-" + amount, player, reason, detail);
        return true;
    }

    /** Sets an exact balance (negative values clamp to zero). */
    public void set(final UUID player, final long amount, final CreditReason reason) {
        balances.put(player, Math.max(0L, amount));
        persist();
        audit("=" + Math.max(0L, amount), player, reason, "");
    }

    /** Comma-grouped display of a Credit amount. */
    public static String format(final long amount) {
        return String.format(java.util.Locale.US, "%,d", amount);
    }

    private void audit(final String delta, final UUID player, final CreditReason reason,
                       final String detail) {
        logger.info("[credits] " + delta + " " + reason.name() + " " + player
                + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")")
                + " -> balance " + balance(player));
    }

    private void persist() {
        try {
            store.saveAll(balances);
        } catch (final IOException exception) {
            logger.severe("Could not save Credits: " + exception.getMessage());
        }
    }

    /** Flushes the store (called from onDisable). */
    public void shutdown() {
        persist();
    }
}
