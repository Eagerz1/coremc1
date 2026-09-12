package com.coremc.core.player;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence backend for player profiles.
 *
 * Implementations must be safe to call off the main thread; the data
 * service serialises calls onto its own executor.
 */
public interface PlayerDataStore {

    /** Loads a profile from disk, or empty if the player has none. */
    Optional<PlayerProfile> load(UUID uuid) throws IOException;

    /** Saves a single profile to disk. */
    void save(PlayerProfile profile) throws IOException;

    /** Saves many profiles; the default implementation loops. */
    default void saveAll(final Collection<PlayerProfile> profiles) throws IOException {
        for (final PlayerProfile profile : profiles) {
            save(profile);
        }
    }

    /**
     * Loads the username index (lowercased name -&gt; last-known UUID).
     * Backends without an index return an empty map.
     */
    default java.util.Map<String, UUID> loadNameIndex() throws IOException {
        return java.util.Map.of();
    }

    /** Persists the username index. */
    default void saveNameIndex(final java.util.Map<String, UUID> index) throws IOException {
        // optional — backends without an index ignore this
    }
}
