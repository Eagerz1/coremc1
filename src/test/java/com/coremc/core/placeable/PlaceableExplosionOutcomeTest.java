package com.coremc.core.placeable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceableExplosionOutcomeTest {

    @Test
    void cancelledAndSurvivingExplosionsKeepOwnershipWhileDestroyedBlocksAreRemoved() {
        assertFalse(PlaceableExplosionOutcome.shouldUnregister(true, false));
        assertFalse(PlaceableExplosionOutcome.shouldUnregister(true, true));
        assertTrue(PlaceableExplosionOutcome.shouldUnregister(false, false));
        assertFalse(PlaceableExplosionOutcome.shouldUnregister(false, true));
    }
}
