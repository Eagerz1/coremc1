package com.coremc.core.spawner;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerProgressionTest {

    private SpawnerTier regular(final long kills) {
        return new SpawnerTier(
                SpawnerTier.tierId("zombie", 1), 1, "&2Zombie Spawner",
                kills, 5L, 1, 400);
    }

    @Test
    void regularSpawnerIdIsStable() {
        assertEquals("zombie-1", SpawnerTier.tierId("zombie", 1));
        assertEquals("zombie-1", regular(25).tierId());
    }

    @Test
    void regularSpawnerValidationRejectsBadConfiguration() {
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
    void regularSpawnerUnlocksAtItsKillRequirement() {
        final SpawnerDefinition mob = new SpawnerDefinition(
                "zombie", "&2Zombie", EntityType.ZOMBIE, Material.ZOMBIE_SPAWN_EGG,
                List.of(regular(25)));

        assertEquals("zombie", mob.killKey());
        assertEquals(0, mob.unlockedTierCount(0));
        assertEquals(0, mob.unlockedTierCount(24));
        assertEquals(1, mob.unlockedTierCount(25));
        assertEquals(1, mob.unlockedTierCount(10_000));

        assertEquals(25L, mob.nextLockedTier(0).orElseThrow().requiredKills());
        assertTrue(mob.nextLockedTier(25).isEmpty(), "regular spawner unlocked");

        assertEquals(1, mob.tier(1).orElseThrow().index());
        assertTrue(mob.tier(0).isEmpty());
        assertTrue(mob.tier(2).isEmpty());
    }

    @Test
    void throughputLineDescribesTheRegularSpawner() {
        assertEquals("spawns 1 every ~20s", regular(25).throughputLine());
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
    void zeroCapDisablesTheGuard() {
        final RollingKillCap cap = new RollingKillCap(0L);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(cap.tryCount(500_000L + i));
        }
    }
}
