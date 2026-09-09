package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-math tests for the BUFF pipeline stage: tier multipliers,
 * delay multipliers, probabilistic count scaling and buff identity.
 * No Bukkit classes touched — safe for the offline runner.
 */
final class IslandBuffMathTest {

    @Test
    void multForTierScalesLinearly() {
        assertEquals(1.0, IslandBuffService.multForTier(0, 10));
        assertEquals(1.0, IslandBuffService.multForTier(-1, 10));
        assertEquals(1.0, IslandBuffService.multForTier(3, 0));
        assertEquals(1.1, IslandBuffService.multForTier(1, 10), 1e-9);
        assertEquals(1.24, IslandBuffService.multForTier(3, 8), 1e-9);
        assertEquals(1.5, IslandBuffService.multForTier(5, 10), 1e-9);
        assertEquals(1.25, IslandBuffService.multForTier(5, 5), 1e-9);
    }

    @Test
    void delayForTierShrinksSmoothly() {
        assertEquals(1.0, IslandBuffService.delayForTier(0, 8));
        assertEquals(1.0, IslandBuffService.delayForTier(2, 0));
        assertEquals(1.0 / 1.4, IslandBuffService.delayForTier(5, 8), 1e-9);
        // strictly decreasing, never zero or negative
        double previous = 1.0;
        for (int tier = 1; tier <= 10; tier++) {
            final double current = IslandBuffService.delayForTier(tier, 8);
            assertTrue(current < previous);
            assertTrue(current > 0.0);
            previous = current;
        }
    }

    @Test
    void scaleCountRespectsBounds() {
        assertEquals(0, IslandBuffService.scaleCount(0, 2.0));
        assertEquals(0, IslandBuffService.scaleCount(-5, 2.0));
        assertEquals(5, IslandBuffService.scaleCount(5, 1.0));
        assertEquals(10, IslandBuffService.scaleCount(10, 0.5));
        // exact fractions are deterministic
        assertEquals(150, IslandBuffService.scaleCount(100, 1.5));
        // fractional parts round fairly to a neighbour
        for (int i = 0; i < 50; i++) {
            final int rolled = IslandBuffService.scaleCount(1, 1.5);
            assertTrue(rolled == 1 || rolled == 2);
            final int elevenish = IslandBuffService.scaleCount(10, 1.05);
            assertTrue(elevenish == 10 || elevenish == 11);
        }
        assertTrue(IslandBuffService.scaleCount(1, 3.0) >= 1);
    }

    @Test
    void buffIdentityNeverCollidesWithUpgrades() {
        assertEquals(12, BuffCatalog.all().size());
        for (final BuffCatalog.Buff buff : BuffCatalog.all()) {
            assertTrue(IslandBuffService.isBuffId(buff.id()));
        }
        assertFalse(IslandBuffService.isBuffId("border"));
        assertFalse(IslandBuffService.isBuffId("member-slots"));
        assertFalse(IslandBuffService.isBuffId("no-such-buff"));
        assertFalse(IslandBuffService.isBuffId(null));
    }
}
