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

    @Test
    void roleProgressRoundTripKeepsEveryRole() {
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Pro", 1L);
        profile.roleId("miner");
        profile.setProgress("miner", 12, 345L);
        profile.setProgress("logger", 4, 10L);
        profile.setOmniToolProgress(7, 55L);

        final PlayerProfile restored = PlayerProfile.fromMap(uuid, profile.toMap());

        assertEquals(12L, ((Number) restored.progressOf("miner").get("level")).longValue());
        assertEquals(345L, ((Number) restored.progressOf("miner").get("xp")).longValue());
        assertEquals(4L, ((Number) restored.progressOf("logger").get("level")).longValue());
        assertEquals("miner", restored.roleId());
        assertEquals(12, restored.roleLevel());
        assertEquals(345L, restored.roleXp());
        assertEquals(7, restored.omniToolLevel());
        assertEquals(55L, restored.omniToolXp());
    }

    @Test
    void killCountsRoundTrip() {
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Hunter", 1L);
        assertEquals(0L, profile.killCountOf("zombie"));
        profile.addKillCount("zombie");
        profile.addKillCount("zombie");
        profile.addKillCount("skeleton");
        assertEquals(2L, profile.killCountOf("zombie"));
        assertEquals(1L, profile.killCountOf("skeleton"));

        final PlayerProfile restored = PlayerProfile.fromMap(uuid, profile.toMap());
        assertEquals(2L, restored.killCountOf("zombie"));
        assertEquals(1L, restored.killCountOf("skeleton"));
        assertEquals(0L, restored.killCountOf("creeper"));
    }

    @Test
    void negativeKillCountsInFilesAreClamped() {
        final java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("kill-counts", java.util.Map.of("zombie", -7L));
        final PlayerProfile restored = PlayerProfile.fromMap(uuid, data);
        assertEquals(0L, restored.killCountOf("zombie"));
    }

    @Test
    void v2RoleFieldsMigrateIntoProgressMap() {
        final java.util.Map<String, Object> v2 = new java.util.LinkedHashMap<>();
        v2.put("username", "Veteran");
        v2.put("first-join-millis", 1L);
        v2.put("role", "miner");
        v2.put("role-level", 9L);
        v2.put("role-xp", 88L);
        final PlayerProfile restored = PlayerProfile.fromMap(uuid, v2);
        assertEquals(9L, ((Number) restored.progressOf("miner").get("level")).longValue());
        assertEquals(88L, ((Number) restored.progressOf("miner").get("xp")).longValue());
    }

    @Test
    void omniUpgradesRoundTripAndDefaults() {
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Upgrader", 1L);
        assertEquals(0, profile.omniUpgrade("efficiency"));
        assertEquals(0, profile.omniUpgrade("never-heard-of-it"));

        profile.setOmniUpgrade("efficiency", 5);
        profile.setOmniUpgrade("smelter", 1);
        profile.setOmniUpgrade("fortune", 0); // 0 = cleared, never persisted

        final PlayerProfile restored = PlayerProfile.fromMap(uuid, profile.toMap());
        assertEquals(5, restored.omniUpgrade("efficiency"));
        assertEquals(1, restored.omniUpgrade("smelter"));
        assertEquals(0, restored.omniUpgrade("fortune"));
        assertFalse(((java.util.Map<?, ?>) profile.toMap().get("omni-upgrades")).containsKey("fortune"));

        // downgrade-to-zero also removes the entry
        restored.setOmniUpgrade("efficiency", 0);
        assertEquals(0, restored.omniUpgrade("efficiency"));

        // v4 profiles (no omni-upgrades key) load with fully-absent upgrades
        final java.util.Map<String, Object> v4 = new java.util.LinkedHashMap<>(profile.toMap());
        v4.remove("omni-upgrades");
        assertEquals(0, PlayerProfile.fromMap(uuid, v4).omniUpgrade("smelter"));
    }
}
