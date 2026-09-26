package com.coremc.core.credits;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Sky Tokens — a whole-number gameplay currency paid out by crates,
 * lootboxes and future progression systems. Same rules as Credits:
 * never negative, persisted on every mutation ({@code sky-tokens.yml}),
 * main-thread only.
 */
public final class SkyTokenService {

    private final BalanceStore store;
    private final Logger logger;
    private final Map<UUID, Long> balances;

    public SkyTokenService(final BalanceStore store, final Logger logger) throws IOException {
        this.store = store;
        this.logger = logger;
        this.balances = store.loadAll();
    }

    /** Current Sky Token balance (0 for unknown players). */
    public long balance(final UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    /** Adds Sky Tokens (amounts ≤ 0 are ignored). */
    public void add(final UUID player, final long amount) {
        if (amount <= 0) {
            return;
        }
        balances.merge(player, amount, Long::sum);
        persist();
    }

    /**
     * Takes Sky Tokens. Returns false (and changes nothing) when the
     * balance is insufficient — balances are never negative.
     */
    public boolean take(final UUID player, final long amount) {
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
        return true;
    }

    private void persist() {
        try {
            store.saveAll(balances);
        } catch (final IOException exception) {
            logger.severe("Could not save Sky Tokens: " + exception.getMessage());
        }
    }

    /** Flushes the store (called from onDisable). */
    public void shutdown() {
        persist();
    }
}
