package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IslandProgressionCatalogTest {

    @Test
    void trackContainsThirtyOrderedLevelsAndStopsAtSeasonCap() {
        assertEquals(30, IslandProgressionCatalog.levels().size());
        assertEquals(1, IslandProgressionCatalog.levelForScore(0L, 100L));
        assertEquals(2, IslandProgressionCatalog.levelForScore(100L, 100L));
        assertEquals(30, IslandProgressionCatalog.levelForScore(Long.MAX_VALUE, 100L));
        assertEquals(84_100L, IslandProgressionCatalog.requiredScore(30, 100L));
        assertEquals("Season Complete", IslandProgressionCatalog.level(30).title());
        assertTrue(IslandProgressionCatalog.levels().stream()
                .allMatch(level -> level.number() >= 1 && level.number() <= 30));
    }

    @Test
    void masteryObjectivesUseExistingPersistedIslandCounters() {
        assertEquals(6, IslandProgressionCatalog.masteryObjectives().size());
        assertTrue(IslandProgressionCatalog.masteryObjectives().stream()
                .allMatch(objective -> objective.target() > 0L && !objective.statKey().isBlank()));
    }
}
