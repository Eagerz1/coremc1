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

    @Test
    void v1MapGetsV2Defaults() {
        // exactly the keys a v0.1 profile file had
        final PlayerProfile restored = PlayerProfile.fromMap(uuid, Map.of(
                "username", "OldTimer",
                "first-join-millis", 10L,
                "last-seen-millis", 20L,
                "total-logins", 3L));
        assertEquals(0L, restored.money());
        assertEquals(0L, restored.credits());
        assertEquals(0L, restored.skyTokens());
        assertEquals("none", restored.roleId());
        assertEquals(1, restored.roleLevel());
        assertEquals(1, restored.omniToolLevel());
        assertEquals(null, restored.islandId());
        assertEquals("none", restored.subscriptionTier());
    }

    @Test
    void v2RoundTripPreservesEconomyAndProgression() {
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Rich", 1L);
        profile.setBalanceInternal(com.coremc.core.economy.Currency.MONEY, 123_456L);
        profile.setBalanceInternal(com.coremc.core.economy.Currency.CREDITS, 42L);
        profile.setBalanceInternal(com.coremc.core.economy.Currency.SKY_TOKENS, 7L);
        profile.islandId(UUID.randomUUID());

        final PlayerProfile restored = PlayerProfile.fromMap(uuid, profile.toMap());

        assertEquals(123_456L, restored.money());
        assertEquals(42L, restored.credits());
        assertEquals(7L, restored.skyTokens());
        assertEquals(profile.islandId(), restored.islandId());
        assertEquals(PlayerProfile.SCHEMA_VERSION, profile.toMap().get("schema-version"));
    }

    @Test
    void negativeBalanceViaInternalSetterIsRejected() {
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "N", 1L);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> profile.setBalanceInternal(com.coremc.core.economy.Currency.CREDITS, -1L));
    }
}
