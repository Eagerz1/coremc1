package com.coremc.core.island;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;

/**
 * Persistence backend for islands.
 *
 * Implementations must be safe to call off the main thread; the island
 * service serialises calls onto its executor (never the main thread).
 */
public interface IslandDataStore {

    /** Loads every island (called once on startup). */
    Collection<Island> loadAll() throws IOException;

    /** Saves one island. */
    void save(Island island) throws IOException;

    /** Deletes the island file of an owner. No-op when absent. */
    void delete(UUID owner) throws IOException;
}
