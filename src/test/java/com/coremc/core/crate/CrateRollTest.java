package com.coremc.core.crate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for crate rolls, chances and rarity helpers.
 * No Bukkit classes touched — safe for the offline runner.
 */
final class CrateRollTest {

    private static CrateReward reward(final int weight, final String rarity) {
        return new CrateReward(CrateReward.RewardType.CURRENCY, weight, rarity,
                "MONEY", 1L, 1L, "", 1, "", "", "label");
    }

    private static CrateDefinition crate(final CrateReward... rewards) {
        return new CrateDefinition("test", "&fTest", "ENDER_CHEST",
                List.of("sky"), List.of(rewards), 10, null);
    }

    @Test
    void totalWeightAndChancePct() {
        final CrateReward common = reward(50, "common");
        final CrateReward rare = reward(25, "rare");
        final CrateReward legendary = reward(25, "legendary");
        final CrateDefinition crate = crate(common, rare, legendary);
        assertEquals(100, crate.totalWeight());
        assertEquals(50, CrateService.chancePct(crate, common));
        assertEquals(25, CrateService.chancePct(crate, rare));
        assertEquals(25, CrateService.chancePct(crate, legendary));
    }

    @Test
    void rollRespectsWeightBoundaries() {
        final CrateReward first = reward(50, "common");
        final CrateReward second = reward(30, "rare");
        final CrateReward third = reward(20, "legendary");
        final CrateDefinition crate = crate(first, second, third);
        assertEquals(first, CrateService.roll(crate, 0.0));
        assertEquals(first, CrateService.roll(crate, 0.4999));
        assertEquals(second, CrateService.roll(crate, 0.50));
        assertEquals(second, CrateService.roll(crate, 0.7999));
        assertEquals(third, CrateService.roll(crate, 0.80));
        assertEquals(third, CrateService.roll(crate, 0.9999));
    }

    @Test
    void rollSkipsZeroWeightAndClamps() {
        final CrateReward zero = reward(0, "common");
        final CrateReward real = reward(10, "rare");
        final CrateDefinition crate = crate(zero, real);
        assertEquals(real, CrateService.roll(crate, 0.0));
        assertEquals(real, CrateService.roll(crate, -5.0));
        assertEquals(real, CrateService.roll(crate, 42.0));
    }

    @Test
    void rollNullOnEmptyPool() {
        assertNull(CrateService.roll(crate(), 0.5));
        assertNull(CrateService.roll(crate(reward(0, "common")), 0.5));
    }

    @Test
    void rarityHelpers() {
        assertEquals("&7", CrateReward.rarityColor("common"));
        assertEquals("&a", CrateReward.rarityColor("Uncommon"));
        assertEquals("&b", CrateReward.rarityColor("rare"));
        assertEquals("&d", CrateReward.rarityColor("epic"));
        assertEquals("&6", CrateReward.rarityColor("legendary"));
        assertEquals("&7", CrateReward.rarityColor("bogus"));
        assertTrue(CrateReward.isJackpot("legendary"));
        assertTrue(CrateReward.isJackpot("LEGENDARY"));
        assertFalse(CrateReward.isJackpot("epic"));
        assertFalse(CrateReward.isJackpot("common"));
    }

    @Test
    void pityStatKeyIsNamespaced() {
        assertEquals("crate-pity:test", crate(reward(1, "common")).pityStatKey());
    }
}
