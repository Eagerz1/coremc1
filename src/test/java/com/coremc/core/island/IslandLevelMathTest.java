package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** Pure math of island levels, speed-track haste and generator-boost yield. No server. */
class IslandLevelMathTest {

    @Test
    void scoreToLevelFollowsOnePlusFloorSqrt() {
        assertEquals(1, IslandProgressService.scoreToLevel(0L, 100L), "zero score is level 1");
        assertEquals(1, IslandProgressService.scoreToLevel(-50L, 100L), "negative score is level 1");
        assertEquals(1, IslandProgressService.scoreToLevel(100L, 0L), "zero divisor is level 1");
        assertEquals(1, IslandProgressService.scoreToLevel(100L, -5L), "negative divisor is level 1");
        assertEquals(1, IslandProgressService.scoreToLevel(99L, 100L), "below one divisor stays 1");
        assertEquals(2, IslandProgressService.scoreToLevel(100L, 100L), "one divisor is level 2");
        assertEquals(2, IslandProgressService.scoreToLevel(399L, 100L), "399 stays 2");
        assertEquals(3, IslandProgressService.scoreToLevel(400L, 100L), "400 reaches 3");
        assertEquals(11, IslandProgressService.scoreToLevel(10_000L, 100L), "sqrt curve holds");
    }

    @Test
    void hasteAmpStepsAtThreeAndFive() {
        assertEquals(-1, IslandActivityEffects.hasteAmpForTier(0), "tier 0 = no effect");
        assertEquals(-1, IslandActivityEffects.hasteAmpForTier(-2), "negative = no effect");
        assertEquals(0, IslandActivityEffects.hasteAmpForTier(1), "tier 1 = Haste I");
        assertEquals(0, IslandActivityEffects.hasteAmpForTier(2), "tier 2 = Haste I");
        assertEquals(1, IslandActivityEffects.hasteAmpForTier(3), "tier 3 = Haste II");
        assertEquals(1, IslandActivityEffects.hasteAmpForTier(4), "tier 4 = Haste II");
        assertEquals(2, IslandActivityEffects.hasteAmpForTier(5), "tier 5 = Haste III");
        assertEquals(2, IslandActivityEffects.hasteAmpForTier(9), "beyond 5 stays III");
    }

    @Test
    void bonusBaseProductsEveryNTiers() {
        assertEquals(0, IslandUpgradeEffects.bonusBaseProducts(0, 2), "tier 0 = none");
        assertEquals(0, IslandUpgradeEffects.bonusBaseProducts(1, 2), "tier 1 = none yet");
        assertEquals(1, IslandUpgradeEffects.bonusBaseProducts(2, 2), "tier 2 = +1");
        assertEquals(2, IslandUpgradeEffects.bonusBaseProducts(5, 2), "tier 5 = +2");
        assertEquals(0, IslandUpgradeEffects.bonusBaseProducts(5, 0), "zero N disables");
        assertEquals(0, IslandUpgradeEffects.bonusBaseProducts(5, -1), "negative N disables");
    }

    @Test
    void prettyNameTitleCasesMaterials() {
        assertEquals("Gold Ingot", IslandUpgradeEffects.prettyName(Material.GOLD_INGOT));
        assertEquals("Diamond", IslandUpgradeEffects.prettyName(Material.DIAMOND));
    }
}
