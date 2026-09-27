package com.coremc.core.achievements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** One player's permanent Achievement state. */
class AchievementProfileTest {

    @Test
    void totalCountersOnlyGrow() {
        final AchievementProfile profile = new AchievementProfile();
        assertEquals(0, profile.counter("quarry_hand"));
        assertEquals(10, profile.add("Quarry_Hand", 10));
        assertEquals(10, profile.add("quarry_hand", 0));
        assertEquals(10, profile.add("quarry_hand", -50));
        assertEquals(35, profile.add("quarry_hand", 25));
        assertEquals(35, profile.counter(" QUARRY_HAND "));
    }

    @Test
    void maxCountersKeepTheHighWaterMark() {
        final AchievementProfile profile = new AchievementProfile();
        assertEquals(10, profile.raise("skyline", 10));
        assertEquals(10, profile.raise("skyline", 4));
        assertEquals(25, profile.raise("skyline", 25));
        assertEquals(25, profile.counter("skyline"));
    }

    @Test
    void uniqueCountersCountDistinctSubjectsOnly() {
        final AchievementProfile profile = new AchievementProfile();
        assertEquals(1, profile.addUnique("bestiary", "zombie"));
        assertEquals(1, profile.addUnique("bestiary", "ZOMBIE"));
        assertEquals(2, profile.addUnique("bestiary", "skeleton"));
        assertEquals(2, profile.counter("bestiary"));
        assertEquals(Set.of("zombie", "skeleton"), profile.uniqueKeys("bestiary"));
        assertTrue(profile.uniqueKeys("ghost").isEmpty());
        profile.putUnique("restored", Set.of("creeper"));
        profile.putUnique("empty", Set.of());
        assertEquals(1, profile.uniqueKeys("restored").size());
        assertEquals(2, profile.uniqueKeys().size());
    }

    @Test
    void earningIsExactlyOnceAndKeepsTheOriginalTimestamp() {
        final AchievementProfile profile = new AchievementProfile();
        assertFalse(profile.earned("first_strike"));
        assertTrue(profile.earn("first_strike", 1_000L, -1));
        assertFalse(profile.earn("first_strike", 9_000L, -1));
        assertEquals(1_000L, profile.earnedAt("first_strike"));
        assertEquals(-1, profile.earnedSeason("first_strike"));
        assertEquals(Set.of("first_strike"), profile.earned());
        assertEquals(1, profile.earnedTimes().size());
    }

    @Test
    void seasonalAchievementsRememberTheirSeasonForever() {
        final AchievementProfile profile = new AchievementProfile();
        profile.earn("season_champion", 5_000L, 3);
        assertEquals(3, profile.earnedSeason("season_champion"));
        assertEquals(Integer.valueOf(3), profile.earnedSeasons().get("season_champion"));
        assertEquals(-1, profile.earnedSeason("never_earned"));
        profile.earn("season_zero", 1L, 0);
        assertEquals(0, profile.earnedSeason("season_zero"));
    }

    @Test
    void claimsAndUnlocksAreExactlyOnce() {
        final AchievementProfile profile = new AchievementProfile();
        assertTrue(profile.claim("first_strike"));
        assertFalse(profile.claim("FIRST_STRIKE"));
        assertTrue(profile.claimed("first_strike"));
        assertTrue(profile.unlock("title:pioneer"));
        assertFalse(profile.unlock("TITLE:PIONEER"));
        assertTrue(profile.unlocked("title:pioneer"));
        assertEquals(1, profile.claims().size());
        assertEquals(1, profile.unlocks().size());
    }

    @Test
    void settingACounterIsTheAdminPathAndZeroClearsIt() {
        final AchievementProfile profile = new AchievementProfile();
        profile.set("quarry_hand", 500);
        assertEquals(500, profile.counter("quarry_hand"));
        profile.set("quarry_hand", 0);
        assertEquals(0, profile.counter("quarry_hand"));
        assertTrue(profile.counters().isEmpty());
    }

    @Test
    void anUntouchedProfileIsEmptySoItIsNeverSaved() {
        final AchievementProfile profile = new AchievementProfile();
        assertTrue(profile.empty());
        profile.earn("first_strike", 1L, -1);
        assertFalse(profile.empty());
    }
}
