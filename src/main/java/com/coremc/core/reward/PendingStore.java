package com.coremc.core.reward;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistence for the pending-reward ledger. */
public interface PendingStore {

    /** Loads all pending transactions (empty when fresh). */
    Map<UUID, List<PendingRewards.PendingTxn>> loadAll() throws IOException;

    /** Persists the whole ledger atomically. */
    void saveAll(Map<UUID, List<PendingRewards.PendingTxn>> pending) throws IOException;
}
