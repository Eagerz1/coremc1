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
}
