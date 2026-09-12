package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pure math of the mining-cube tiers and the generator/spawner boost curves. No server. */
class MiningCubeMathTest {

    @Test
    void cubeSizeCapsAtFive() {
        assertEquals(0, MiningCubeService.sizeForTier(0), "tier 0 = no cube");
        assertEquals(0, MiningCubeService.sizeForTier(-1), "negative = no cube");
        assertEquals(2, MiningCubeService.sizeForTier(1), "tier 1 = 2 edge");
        assertEquals(5, MiningCubeService.sizeForTier(4), "tier 4 = 5x5");
        assertEquals(5, MiningCubeService.sizeForTier(5), "tier 5 stays 5x5");
        assertEquals(5, MiningCubeService.sizeForTier(15), "tier 15 stays 5x5");
    }

    @Test
    void regenCadenceSpeedsUpToTheFloor() {
        assertEquals(30, MiningCubeService.regenSecondsFor(1, 30, 2, 5), "tier 1 = base");
        assertEquals(16, MiningCubeService.regenSecondsFor(8, 30, 2, 5), "tier 8 = base - 7 steps");
        assertEquals(5, MiningCubeService.regenSecondsFor(15, 30, 2, 5), "tier 15 hits the floor");
        assertEquals(5, MiningCubeService.regenSecondsFor(15, 30, 99, 5), "floor holds for huge steps");
    }

    @Test
    void blocksPerTickStepsAtSixAndEleven() {
        assertEquals(1, MiningCubeService.blocksPerTick(1));
        assertEquals(1, MiningCubeService.blocksPerTick(5));
        assertEquals(2, MiningCubeService.blocksPerTick(6));
        assertEquals(2, MiningCubeService.blocksPerTick(10));
        assertEquals(3, MiningCubeService.blocksPerTick(11));
        assertEquals(3, MiningCubeService.blocksPerTick(15));
    }

    @Test
    void spawnerDelayReductionFloorsAtOneSecond() {
        assertEquals(200, IslandUpgradeEffects.reducedDelayTicks(200, 0, 10), "tier 0 = base");
        assertEquals(200, IslandUpgradeEffects.reducedDelayTicks(200, 5, 0), "zero pct = base");
        assertEquals(100, IslandUpgradeEffects.reducedDelayTicks(200, 5, 10), "5 tiers x 10% = half");
        assertEquals(20, IslandUpgradeEffects.reducedDelayTicks(30, 5, 50), "floors at 20 ticks");
    }

    @Test
    void generatorCooldownReductionFloorsAtOneSecond() {
        assertEquals(60L, IslandUpgradeEffects.reducedCooldownSeconds(60L, 0, 8), "tier 0 = base");
        assertEquals(36L, IslandUpgradeEffects.reducedCooldownSeconds(60L, 5, 8), "5 tiers x 8% = 36s");
        assertEquals(1L, IslandUpgradeEffects.reducedCooldownSeconds(1L, 5, 8), "floors at 1s");
    }
}
