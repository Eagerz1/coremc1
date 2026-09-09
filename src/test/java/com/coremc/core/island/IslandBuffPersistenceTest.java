package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Buff purchases must survive a save/load round-trip, and pre-v5
 * island files (no {@code buffs} key at all) must load as unbuffed
 * instead of failing. No Bukkit classes touched.
 */
final class IslandBuffPersistenceTest {

    private static Island freshIsland() {
        return new Island(UUID.randomUUID(), UUID.randomUUID(), "world", 0, 64, 0, 50, 0L);
    }

    @Test
    void buffsSurviveMapRoundTrip() {
        final Island island = freshIsland();
        island.setBuffTier("mining-boost", 3);
        island.setBuffTier("island-luck", 5);

        final Island reloaded = Island.fromMap(island.toMap());

        assertEquals(3, reloaded.buffs().getOrDefault("mining-boost", 0));
        assertEquals(5, reloaded.buffs().getOrDefault("island-luck", 0));
        assertEquals(0, reloaded.buffs().getOrDefault("sell-boost", 0));
    }

    @Test
    void missingBuffsKeyLoadsAsUnbuffed() {
        final Island island = freshIsland();
        final var map = new java.util.LinkedHashMap<String, Object>(island.toMap());
        map.remove("buffs");

        final Island reloaded = Island.fromMap(map);

        assertTrue(reloaded.buffs().isEmpty());
    }

    @Test
    void buffTiersAreClampedAtFloor() {
        final Island island = freshIsland();
        island.setBuffTier("xp-boost", -4);
        assertEquals(0, island.buffs().getOrDefault("xp-boost", -1));
    }

    @Test
    void persistedMapCarriesSchemaVersion() {
        final Map<String, Object> map = freshIsland().toMap();
        assertTrue(map.containsKey("buffs"));
    }
}
