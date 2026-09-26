package com.coremc.core.credits;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Whole-number balance persistence (Credits, Sky Tokens): load everything
 * at enable, write everything back after each transaction — the same
 * deliberately simple contract as the coin economy's store.
 */
public interface BalanceStore {

    /** Loads all persisted balances (an empty map when the store is fresh). */
    Map<UUID, Long> loadAll() throws IOException;

    /** Persists the given balances atomically. */
    void saveAll(Map<UUID, Long> balances) throws IOException;
}
