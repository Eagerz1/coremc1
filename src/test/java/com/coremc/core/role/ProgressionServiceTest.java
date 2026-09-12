package com.coremc.core.role;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressionServiceTest {

    private final ProgressionService progression = new ProgressionService();

    @Test
    void curveGrowsWithLevel() {
        final long level1 = progression.xpForNext(100, 1);
        final long level5 = progression.xpForNext(100, 5);
        final long level10 = progression.xpForNext(100, 10);
        assertTrue(level5 > level1);
        assertTrue(level10 > level5);
        assertEquals(100L, level1); // 100 * 1^1.5
    }

    @Test
    void awardBelowLevelUpKeepsLevel() {
        final ProgressionService.Result result = progression.award(100, 1, 40, 30);
        assertEquals(1, result.level);
        assertEquals(70, result.xp);
        assertEquals(0, result.levelsGained);
    }

    @Test
    void awardCrossingLevelUpCarriesRemainder() {
        // 100 needed; have 90, gain 25 => level 2 with 15 xp
        final ProgressionService.Result result = progression.award(100, 1, 90, 25);
        assertEquals(2, result.level);
        assertEquals(15, result.xp);
        assertEquals(1, result.levelsGained);
    }

    @Test
    void multipleLevelUpsInOneAward() {
        final ProgressionService.Result result = progression.award(100, 1, 0, 1_000_000);
        assertTrue(result.levelsGained > 5);
        assertTrue(result.xp < progression.xpForNext(100, result.level));
    }

    @Test
    void capAtMaxLevelDiscardsXp() {
        final ProgressionService.Result result =
                progression.award(100, ProgressionService.MAX_LEVEL, 0, 5000);
        assertTrue(result.capped);
        assertEquals(0, result.levelsGained);
        assertEquals(ProgressionService.MAX_LEVEL, result.level);
    }

    @Test
    void negativeAmountRejected() {
        assertThrows(IllegalArgumentException.class, () -> progression.award(100, 1, 0, -5));
    }

    @Test
    void zeroAmountIsAQuietNoOp() {
        final ProgressionService.Result result = progression.award(100, 3, 200, 0);
        assertEquals(3, result.level);
        assertEquals(200, result.xp);
        assertFalse(result.capped);
    }
}
