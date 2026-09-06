package com.coremc.core.player;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProfileTest {

    private final UUID uuid = UUID.randomUUID();

    @Test
    void newProfileStartsEmpty() {
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Steve", 1_000L);
        assertEquals("Steve", profile.username());
        assertEquals(1_000L, profile.firstJoinMillis());
        assertEquals(0L, profile.totalLogins());
        assertTrue(profile.isFirstLogin());
    }

    @Test
    void recordLoginIncrementsAndUpdates() {
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Steve", 1_000L);
        profile.recordLogin("SteveNew", 2_000L);
        assertEquals(1L, profile.totalLogins());
        assertEquals("SteveNew", profile.username());
        assertEquals(2_000L, profile.lastSeenMillis());
        assertFalse(profile.isFirstLogin());
    }

    @Test
    void mapRoundTripPreservesAllFields() {
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Alex", 5_000L);
        profile.recordLogin("Alex", 6_000L);
        profile.recordQuit(7_000L);

        final PlayerProfile restored = PlayerProfile.fromMap(uuid, profile.toMap());

        assertEquals(profile.uuid(), restored.uuid());
        assertEquals(profile.username(), restored.username());
        assertEquals(profile.firstJoinMillis(), restored.firstJoinMillis());
        assertEquals(profile.lastSeenMillis(), restored.lastSeenMillis());
        assertEquals(profile.totalLogins(), restored.totalLogins());
    }

    @Test
    void fromMapToleratesStringNumbersAndMissingKeys() {
        final PlayerProfile restored = PlayerProfile.fromMap(uuid, Map.of(
                "username", "Legacy",
                "first-join-millis", "1234",
                "total-logins", "9"));
        assertEquals(1_234L, restored.firstJoinMillis());
        assertEquals(9L, restored.totalLogins());
        assertEquals(0L, restored.lastSeenMillis());
    }
}
