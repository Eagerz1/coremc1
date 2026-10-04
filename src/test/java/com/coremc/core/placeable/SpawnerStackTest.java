package com.coremc.core.placeable;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpawnerStackTest {

    @Test
    void placementStackCountsAreClampedToOneThroughThreeThousand() {
        final UUID owner = UUID.randomUUID();
        assertEquals(1, new PlaceableService.Placement(
                PlaceableService.Type.SPAWNER, "zombie-1", owner, 0, 64, 0, 0).stackCount());
        assertEquals(3000, new PlaceableService.Placement(
                PlaceableService.Type.SPAWNER, "zombie-1", owner, 0, 64, 0, 9999).stackCount());
    }
}
