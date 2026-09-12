package com.coremc.core.spawner;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerProgressionTest {

    private SpawnerTier tier(final int index, final long kills) {
        return new SpawnerTier(
                SpawnerTier.tierId("zombie", index), index, "&2Zombie Spawner " + index,
                kills, 5L * index, index, Math.max(20, 500 - index * 80));
    }

    @Test
    void tierIdsAreStablePurchasableKeys() {
        assertEquals("zombie-1", SpawnerTier.tierId("zombie", 1));
        assertEquals("creeper-4", SpawnerTier.tierId("creeper", 4));
        assertEquals("zombie-2", tier(2, 60).tierId());
    }

    @Test
    void tierValidationRejectsBadThroughput() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpawnerTier("x-1", 1, "&fX", 25, 5, 0, 400)); // spawn-count 0
        assertThrows(IllegalArgumentException.class,
                () -> new SpawnerTier("x-1", 1, "&fX", 25, 5, 9, 400)); // spawn-count 9
        assertThrows(IllegalArgumentException.class,
                () -> new SpawnerTier("x-1", 1, "&fX", 25, 5, 1, 10)); // < 1s cycle
        assertThrows(IllegalArgumentException.class,
                () -> new SpawnerTier("x-1", 1, "&fX", -1, 5, 1, 400)); // negative kills
        assertThrows(IllegalArgumentException.class,
                () -> new SpawnerTier("x-1", 1, "&fX", 25, -5, 1, 400)); // negative price
    }

    @Test
    void laneUnlockMathProgressesStrictly() {
        final SpawnerDefinition mob = new SpawnerDefinition(
                "zombie", "&2Zombie", EntityType.ZOMBIE, Material.ZOMBIE_SPAWN_EGG,
                List.of(tier(1, 25), tier(2, 60), tier(3, 120), tier(4, 200)));

        assertEquals("zombie", mob.killKey());
        assertEquals(0, mob.unlockedTierCount(0));
        assertEquals(0, mob.unlockedTierCount(24));
        assertEquals(1, mob.unlockedTierCount(25));
        assertEquals(2, mob.unlockedTierCount(60));
        assertEquals(3, mob.unlockedTierCount(199));
        assertEquals(4, mob.unlockedTierCount(10_000));

        assertEquals(25L, mob.nextLockedTier(0).orElseThrow().requiredKills());
        assertEquals(200L, mob.nextLockedTier(199).orElseThrow().requiredKills());
        assertTrue(mob.nextLockedTier(200).isEmpty(), "lane maxed at tier IV");

        assertEquals(2, mob.tier(2).orElseThrow().index());
        assertTrue(mob.tier(0).isEmpty());
        assertTrue(mob.tier(5).isEmpty());
    }

    @Test
    void throughputLineDocumentsBetterTiers() {
        // tier(i) helper builds delay = 500 - i*80 ticks (i=1 -> 420 -> 21s)
        assertEquals("spawns 1 every ~21s", tier(1, 25).throughputLine());
        assertEquals("spawns 4 every ~9s", tier(4, 200).throughputLine());
    }

    @Test
    void romanNumeralsCoverTheLane() {
        assertEquals("I", SpawnerTier.roman(1));
        assertEquals("II", SpawnerTier.roman(2));
        assertEquals("III", SpawnerTier.roman(3));
        assertEquals("IV", SpawnerTier.roman(4));
    }

    @Test
    void rollingKillCapCountsOnlyWithinWindow() {
        final RollingKillCap cap = new RollingKillCap(3L);
        final long t0 = 1_000_000L;
        assertTrue(cap.tryCount(t0));
        assertTrue(cap.tryCount(t0 + 1_000));
        assertTrue(cap.tryCount(t0 + 2_000));
        // 4th kill inside the same rolling minute is NOT counted
        assertTrue(!cap.tryCount(t0 + 3_000));
        assertTrue(!cap.tryCount(t0 + 59_000));
        // exactly one minute after the first admit the window opens again
        assertTrue(cap.tryCount(t0 + 60_000));
        // still capped (2nd admit was at +1s)
        assertTrue(!cap.tryCount(t0 + 60_000));
        assertTrue(cap.tryCount(t0 + 61_000));
    }

    @Test
    void ancientTierIsMarkedAndDescribed() {
        final SpawnerTier ancient = new SpawnerTier(
                SpawnerTier.tierId("zombie", 5), 5, "&5&lAncient Zombie Spawner",
                275, 100, 1, 200, true);
        assertTrue(ancient.ancient());
        assertEquals("awakens an Ancient mob every ~10s", ancient.throughputLine());
        assertTrue(ancient.specialLine().toLowerCase().contains("triple progress"),
                "ancient lore explains the triple-progress rule");
    }

    @Test
    void legacySevenArgConstructorDefaultsToNonAncient() {
        final SpawnerTier plain = new SpawnerTier("zombie-1", 1, "&2Zombie Spawner I", 25, 5, 1, 400);
        assertFalse(plain.ancient());
        assertEquals("spawns 1 every ~20s", plain.throughputLine());
        assertEquals("", plain.specialLine());
    }

    @Test
    void fiveTierLaneUnlocksIncludingAncient() {
        final SpawnerDefinition mob = new SpawnerDefinition(
                "zombie", "&2Zombie", EntityType.ZOMBIE, Material.ZOMBIE_SPAWN_EGG,
                List.of(tier(1, 25), tier(2, 60), tier(3, 120), tier(4, 200),
                        new SpawnerTier(SpawnerTier.tierId("zombie", 5), 5,
                                "&5Ancient", 275, 100, 1, 200, true)));
        assertEquals(5, mob.tiers().size());
        assertEquals(4, mob.unlockedTierCount(200));
        assertEquals(5, mob.unlockedTierCount(275));
        assertEquals(275L, mob.nextLockedTier(200).orElseThrow().requiredKills());
        assertTrue(mob.nextLockedTier(275).isEmpty());
        assertEquals("V", SpawnerTier.roman(5));
    }

    @Test
    void zeroCapDisablesTheGuard() {
        final RollingKillCap cap = new RollingKillCap(0L);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(cap.tryCount(500_000L + i));
        }
    }
}
