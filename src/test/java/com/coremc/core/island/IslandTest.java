package com.coremc.core.island;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IslandTest {

    private final UUID islandId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @Test
    void mapRoundTripPreservesAllFields() {
        final Island island = new Island(islandId, owner, "world", 256, 64, -512, 1_234_567L);
        final Island restored = Island.fromMap(island.toMap());

        assertEquals(island.islandId(), restored.islandId());
        assertEquals(island.owner(), restored.owner());
        assertEquals(island.worldName(), restored.worldName());
        assertEquals(island.centerX(), restored.centerX());
        assertEquals(island.centerY(), restored.centerY());
        assertEquals(island.centerZ(), restored.centerZ());
        assertEquals(island.createdMillis(), restored.createdMillis());
    }

    @Test
    void gridCellUsesFloorDivision() {
        final Island island = new Island(islandId, owner, "world", -128, 64, 300, 0L);
        assertArrayEquals(new int[] {-1, 1}, island.gridCell(256));
    }

    @Test
    void homeIsAboveCentre() {
        final Island island = new Island(islandId, owner, "world", 256, 64, 256, 0L);
        assertEquals(256.5, island.homeX());
        assertEquals(65.0, island.homeY());
        assertEquals(256.5, island.homeZ());
    }
}
