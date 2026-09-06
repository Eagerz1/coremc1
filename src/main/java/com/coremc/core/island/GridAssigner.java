package com.coremc.core.island;

import java.util.Set;

/**
 * Assigns free island grid cells in spiral order around the origin.
 *
 * Cells are integer (i, j) grid coordinates; world coordinates are the
 * cell multiplied by the island spacing. The spiral keeps early islands
 * close together for a compact spawn region.
 */
public final class GridAssigner {

    private GridAssigner() {
    }

    /**
     * Returns the first unused cell in spiral order for {@code world}:
     * (0,0), (1,0), (1,1), (0,1), (-1,1), (-1,0), (-1,-1), (0,-1), (1,-1), ...
     * The set contains cell keys produced by {@link #key(int, int, String)}.
     */
    public static int[] nextFreeCell(final Set<String> usedCells, final String worldName) {
        int x = 0;
        int z = 0;
        // direction cycle: +x, +z, -x, -z (spiral outwards)
        int dx = 1, dz = 0;
        int legLength = 1, stepsInLeg = 0, legsCompleted = 0;

        for (long guard = 0; guard < 1_000_000L; guard++) {
            if (!usedCells.contains(key(x, z, worldName))) {
                return new int[] {x, z};
            }
            x += dx;
            z += dz;
            if (++stepsInLeg == legLength) {
                stepsInLeg = 0;
                legsCompleted++;
                final int tmp = dx;
                dx = -dz;
                dz = tmp;
                if (legsCompleted % 2 == 0) {
                    legLength++;
                }
            }
        }
        throw new IllegalStateException("No free island cell found inside spiral guard");
    }

    public static String key(final int cellX, final int cellZ, final String worldName) {
        return cellX + ":" + cellZ + "@" + worldName;
    }
}
