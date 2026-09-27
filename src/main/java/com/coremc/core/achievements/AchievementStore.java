package com.coremc.core.achievements;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for permanent Achievement profiles, behind an interface
 * so the service is unit-testable with an in-memory store.
 */
public interface AchievementStore {

    /** Loads every stored profile. */
    Map<UUID, AchievementProfile> load() throws IOException;

    /** Writes every profile atomically. */
    void save(Map<UUID, AchievementProfile> profiles) throws IOException;
}
