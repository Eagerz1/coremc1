package com.coremc.core.spawner;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spawner-system persistence: island luck levels and placed spawner
 * registrations. Small files, written atomically after each change.
 */
public interface SpawnerDataStore {

    /** Island id -> luck level. */
    Map<UUID, Integer> loadLuck() throws IOException;

    /** All placed spawner registrations. */
    List<SpawnerEntry> loadSpawners() throws IOException;

    /** Persists everything atomically. */
    void save(Map<UUID, Integer> luck, List<SpawnerEntry> spawners) throws IOException;
}
