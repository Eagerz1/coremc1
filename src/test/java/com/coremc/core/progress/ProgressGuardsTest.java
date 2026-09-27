package com.coremc.core.progress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The anti-exploit rules. Every one of them is a pure predicate. */
class ProgressGuardsTest {

    @Test
    void onlySurvivalAndAdventureCount() {
        assertTrue(ProgressGuards.validGameMode("SURVIVAL"));
        assertTrue(ProgressGuards.validGameMode("adventure"));
        assertFalse(ProgressGuards.validGameMode("CREATIVE"));
        assertFalse(ProgressGuards.validGameMode("SPECTATOR"));
        assertFalse(ProgressGuards.validGameMode(null));
    }

    @Test
    void deniedWorldsNeverCountAndAnEmptyAllowListMeansEverywhere() {
        assertTrue(ProgressGuards.validWorld("coremc_islands", List.of(), List.of()));
        assertFalse(ProgressGuards.validWorld("creative_plots", List.of(),
                List.of("creative_plots")));
        assertTrue(ProgressGuards.validWorld("coremc_islands", List.of("coremc_islands"),
                List.of()));
        assertFalse(ProgressGuards.validWorld("world_nether", List.of("coremc_islands"),
                List.of()));
        assertFalse(ProgressGuards.validWorld(" ", List.of(), List.of()));
        assertFalse(ProgressGuards.validWorld(null, null, null));
    }

    @Test
    void sceneryEntitiesAreNeverSlayerKills() {
        assertTrue(ProgressGuards.validMobType("ZOMBIE"));
        assertFalse(ProgressGuards.validMobType("PLAYER"));
        assertFalse(ProgressGuards.validMobType("ARMOR_STAND"));
        assertFalse(ProgressGuards.validMobType("TEXT_DISPLAY"));
        assertFalse(ProgressGuards.validMobType("ITEM_DISPLAY"));
        assertFalse(ProgressGuards.validMobType("BLOCK_DISPLAY"));
        assertFalse(ProgressGuards.validMobType("INTERACTION"));
        assertFalse(ProgressGuards.validMobType("ITEM_FRAME"));
        assertFalse(ProgressGuards.validMobType("DROPPED_ITEM"));
        assertFalse(ProgressGuards.validMobType(""));
        assertFalse(ProgressGuards.validMobType(null));
    }

    @Test
    void playerPlacedBlocksNeverCountAsMining() {
        assertTrue(ProgressGuards.countableMining(false, true));
        assertFalse(ProgressGuards.countableMining(true, true));
        assertFalse(ProgressGuards.countableMining(false, false));
    }

    @Test
    void onlyRipeCropsCountAsHarvests() {
        assertTrue(ProgressGuards.countableHarvest(false, true));
        assertFalse(ProgressGuards.countableHarvest(false, false));
        assertFalse(ProgressGuards.countableHarvest(true, true));
    }

    @Test
    void killsMustBePlayerCausedAndAgainstRealMobs() {
        assertTrue(ProgressGuards.countableKill(true, "SKELETON"));
        assertFalse(ProgressGuards.countableKill(false, "SKELETON"));
        assertFalse(ProgressGuards.countableKill(true, "ARMOR_STAND"));
    }

    @Test
    void amountsMustBePositiveAndSane() {
        assertTrue(ProgressGuards.validAmount(1));
        assertTrue(ProgressGuards.validAmount(999_999));
        assertFalse(ProgressGuards.validAmount(0));
        assertFalse(ProgressGuards.validAmount(-3));
        assertFalse(ProgressGuards.validAmount(1_000_000));
        assertFalse(ProgressGuards.validAmount(Long.MAX_VALUE));
    }

    @Test
    void theWholeWorldGateCombinesEveryRule() {
        assertTrue(ProgressGuards.validWorldAction("SURVIVAL", "coremc_islands", List.of(),
                List.of(), 1));
        assertFalse(ProgressGuards.validWorldAction("CREATIVE", "coremc_islands", List.of(),
                List.of(), 1));
        assertFalse(ProgressGuards.validWorldAction("SURVIVAL", "spawn", List.of(),
                List.of("spawn"), 1));
        assertFalse(ProgressGuards.validWorldAction("SURVIVAL", "coremc_islands", List.of(),
                List.of(), 0));
    }
}
