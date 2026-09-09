package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Grid-lookup tests for the islandAt repair: island centres sit on grid
 * corners, so every border quadrant must resolve to the island's cell.
 * Pure integer math — no Bukkit classes touched.
 */
final class IslandGridTest {

    private static Set<String> keys(final List<int[]> cells) {
        final Set<String> keys = new HashSet<>();
        for (final int[] cell : cells) {
            keys.add(cell[0] + "," + cell[1]);
        }
        return keys;
    }

    @Test
    void candidateCellsCoverAllFourQuadrants() {
        // Island centred on the (0,0) corner with spacing 256: a point in
        // EVERY quadrant around the centre must list (0,0) as a candidate.
        assertTrue(keys(IslandService.candidateCells(0, 0, 256)).contains("0,0")); // centre
        assertTrue(keys(IslandService.candidateCells(100, 100, 256)).contains("0,0")); // SE
        assertTrue(keys(IslandService.candidateCells(-100, 100, 256)).contains("0,0")); // SW
        assertTrue(keys(IslandService.candidateCells(100, -100, 256)).contains("0,0")); // NE
        assertTrue(keys(IslandService.candidateCells(-100, -100, 256)).contains("0,0")); // NW
        assertTrue(keys(IslandService.candidateCells(-1, -1, 256)).contains("0,0")); // edge hugging
    }

    @Test
    void candidateCellsExcludeFarCells() {
        // Points a full cell away never probe the island's cell.
        assertTrue(keys(IslandService.candidateCells(300, 300, 256)).stream().noneMatch("0,0"::equals));
        assertTrue(keys(IslandService.candidateCells(-300, -300, 256)).stream().noneMatch("0,0"::equals));
    }

    @Test
    void candidateCellsAlwaysProbeFour() {
        assertEquals(4, IslandService.candidateCells(7, -42, 256).size());
        assertEquals(4, IslandService.candidateCells(0, 0, 64).size());
    }

    @Test
    void negativeCoordinatesBehave() {
        // floorDiv semantics: -1 sits in cell -1, whose +1 neighbour is 0.
        assertTrue(keys(IslandService.candidateCells(-1, -1, 256)).contains("0,0"));
        assertTrue(keys(IslandService.candidateCells(-256, -256, 256)).contains("-1,-1"));
    }
}
