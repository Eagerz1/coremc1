package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IslandLevelTrackTest {

    @Test
    void definesThirtyOrderedLevelsWithStableThresholds() {
        final var levels = IslandLevelTrack.levels(100L);

        assertEquals(30, levels.size());
        assertEquals(1L, levels.get(0).requiredScore());
        assertEquals(400L, levels.get(2).requiredScore());
        assertEquals(84_100L, levels.get(29).requiredScore());
        assertEquals("CoreMC Legend", levels.get(29).title());
    }

    @Test
    void levelsAtThresholdsAndCapsAtSeasonMaximum() {
        assertEquals(1, IslandLevelTrack.levelForScore(0L, 100L));
        assertEquals(2, IslandLevelTrack.levelForScore(100L, 100L));
        assertEquals(2, IslandLevelTrack.levelForScore(399L, 100L));
        assertEquals(3, IslandLevelTrack.levelForScore(400L, 100L));
        assertEquals(30, IslandLevelTrack.levelForScore(84_100L, 100L));
        assertEquals(30, IslandLevelTrack.levelForScore(Long.MAX_VALUE, 100L));
        assertEquals(1, IslandLevelTrack.levelForScore(Long.MAX_VALUE, 0L));
    }

    @Test
    void exposesKeyProgressionUnlocksAtTheirDefinedLevels() {
        assertTrue(IslandLevelTrack.level(3, 100L).orElseThrow().unlocks().contains("generator-tier-2"));
        assertTrue(IslandLevelTrack.level(5, 100L).orElseThrow().unlocks().contains("slayer-section-2"));
        assertTrue(IslandLevelTrack.level(8, 100L).orElseThrow().unlocks().contains("companions"));
        assertTrue(IslandLevelTrack.level(10, 100L).orElseThrow().unlocks().contains("equipment-sets"));
        assertTrue(IslandLevelTrack.level(15, 100L).orElseThrow().unlocks().contains("advanced-spawners"));
        assertFalse(IslandLevelTrack.level(31, 100L).isPresent());
    }
}
