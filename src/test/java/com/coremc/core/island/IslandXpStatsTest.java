package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Island XP + lifetime stats persist and load tolerantly. */
class IslandXpStatsTest {

    @Test
    void xpAndStatsRoundTrip() {
        final Island island = new Island(
                UUID.randomUUID(), UUID.randomUUID(), "islands", 0, 64, 0, 50, System.currentTimeMillis());
        island.addXp(1250L);
        island.addStat("blocks-mined", 300L);
        island.addStat("crops-harvested", 45L);

        final Island reloaded = Island.fromMap(island.toMap());

        assertEquals(1250L, reloaded.xp());
        assertEquals(300L, reloaded.statOf("blocks-mined"));
        assertEquals(45L, reloaded.statOf("crops-harvested"));
        assertEquals(0L, reloaded.statOf("never-tracked"));
    }

    @Test
    void legacyMapsDefaultToZero() {
        final Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("island-id", UUID.randomUUID().toString());
        legacy.put("owner", UUID.randomUUID().toString());
        legacy.put("world", "islands");
        legacy.put("center-x", 0);
        legacy.put("center-y", 64);
        legacy.put("center-z", 0);
        legacy.put("created-millis", 0L);

        final Island island = Island.fromMap(legacy);

        assertEquals(0L, island.xp());
        assertTrue(island.stats().isEmpty());
    }

    @Test
    void negativeAmountsAreIgnored() {
        final Island island = new Island(
                UUID.randomUUID(), UUID.randomUUID(), "islands", 0, 64, 0, 50, System.currentTimeMillis());
        island.addXp(-50L);
        island.addStat("blocks-mined", -10L);
        assertEquals(0L, island.xp());
        assertEquals(0L, island.statOf("blocks-mined"));
    }
}
