package com.coremc.core.essence;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for {@link EssenceManager}: player UUIDs to their
 * essence profile (slayer / mining / farming / kills).
 */
public interface EssenceStore {

    /** Loads every stored profile; a missing file yields an empty map. */
    Map<UUID, EssenceProfile> loadAll() throws IOException;

    /** Atomically writes every profile. */
    void saveAll(Map<UUID, EssenceProfile> profiles) throws IOException;
}
