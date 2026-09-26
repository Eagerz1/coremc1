package com.coremc.core.gens;

import java.io.IOException;
import java.util.List;

/**
 * Generator persistence: every placed generator registration. A small
 * file, written atomically after each change — the same contract the
 * spawner store follows.
 */
public interface GeneratorDataStore {

    /** All placed generator registrations. */
    List<GeneratorEntry> load() throws IOException;

    /** Persists everything atomically. */
    void save(List<GeneratorEntry> generators) throws IOException;
}
