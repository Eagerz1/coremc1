package com.coremc.core.collections;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for permanent Collection profiles. Kept behind an
 * interface so the service can be unit-tested with an in-memory store
 * and so a future database store is a drop-in replacement.
 */
public interface CollectionStore {

    /** Loads every stored profile. */
    Map<UUID, CollectionProfile> load() throws IOException;

    /** Writes every profile atomically. */
    void save(Map<UUID, CollectionProfile> profiles) throws IOException;
}
