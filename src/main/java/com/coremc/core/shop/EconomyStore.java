package com.coremc.core.shop;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Coin balance persistence: the whole economy is one small file, so
 * the contract is deliberately simple — load everything at enable,
 * write everything back after each transaction.
 */
public interface EconomyStore {

    /** Loads all persisted balances (an empty map when the store is fresh). */
    Map<UUID, Double> loadAll() throws IOException;

    /** Persists the given balances atomically. */
    void saveAll(Map<UUID, Double> balances) throws IOException;
}
