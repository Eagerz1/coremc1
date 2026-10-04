package com.coremc.core.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionProgressionTest {

    @Test
    void carriesXpAcrossMultipleLevels() {
        final CompanionProgression.Result result = CompanionProgression.award(1, 90, 230, 20);
        assertEquals(3, result.level());
        assertEquals(20L, result.xp());
        assertEquals(2, result.levelsGained());
    }

    @Test
    void maxLevelStopsAndClearsUnusedXp() {
        final CompanionProgression.Result result = CompanionProgression.award(19, 1800, 500, 20);
        assertEquals(20, result.level());
        assertEquals(0L, result.xp());
        assertEquals(1, result.levelsGained());
    }

    @Test
    void invalidNegativeAwardsCannotReduceProgress() {
        final CompanionProgression.Result result = CompanionProgression.award(4, 75, -100, 20);
        assertEquals(4, result.level());
        assertEquals(75L, result.xp());
    }
}
