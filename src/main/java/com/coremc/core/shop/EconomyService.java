package com.coremc.core.shop;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Coin balances for all players. Players who have never transacted
 * implicitly hold the configured starting balance, so a fresh install
 * needs no seed data. Every mutation is rounded to cents and written
 * through to the store immediately — the file is tiny, so durability
 * beats batching.
 */
public final class EconomyService {

    private final EconomyStore store;
    private final Logger logger;
    private final double startingBalance;
    private final Map<UUID, Double> balances;

    public EconomyService(final EconomyStore store, final double startingBalance, final Logger logger)
            throws IOException {
        this.store = store;
        this.logger = logger;
        this.startingBalance = Money.round(Math.max(0, startingBalance));
        this.balances = store.loadAll();
    }

    /** Current balance; the starting balance for unknown players. */
    public double balance(final UUID player) {
        return balances.getOrDefault(player, startingBalance);
    }

    /** True if the player holds at least {@code amount}. */
    public boolean has(final UUID player, final double amount) {
        return balance(player) + 1e-9 >= amount;
    }

    /**
     * Withdraws coins. Returns false (and changes nothing) when the
     * balance is insufficient — callers message the player themselves.
     */
    public boolean withdraw(final UUID player, final double amount) {
        final double rounded = Money.round(amount);
        if (rounded < 0 || !has(player, rounded)) {
            return false;
        }
        set(player, balance(player) - rounded);
        return true;
    }

    /** Deposits coins (never negative). */
    public void deposit(final UUID player, final double amount) {
        final double rounded = Money.round(amount);
        if (rounded < 0) {
            return;
        }
        set(player, balance(player) + rounded);
    }

    private void set(final UUID player, final double value) {
        balances.put(player, Money.round(value));
        persist();
    }

    private void persist() {
        try {
            store.saveAll(balances);
        } catch (final IOException exception) {
            logger.severe("Could not save balances: " + exception.getMessage());
        }
    }

    /** Flushes the store (called from onDisable). */
    public void shutdown() {
        persist();
    }
}
