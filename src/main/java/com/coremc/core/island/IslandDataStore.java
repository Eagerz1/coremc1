package com.coremc.core.island;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;

/** Persistence for islands. */
public interface IslandDataStore {

    /** Loads every stored island (skipping files that fail to parse, with a logged warning). */
    Collection<Island> loadAll() throws IOException;

    /** Atomically writes the island's file. */
    void save(Island island) throws IOException;

    /** Removes the island's file. */
    void delete(UUID owner) throws IOException;

    /**
     * The persisted slot high-water mark: every slot below it has been
     * handed out at some point, so it must never be reused (a deleted
     * island's blocks stay in the world).
     */
    long loadNextSlot();

    /** Persists the slot high-water mark. */
    void saveNextSlot(long nextSlot);
}
