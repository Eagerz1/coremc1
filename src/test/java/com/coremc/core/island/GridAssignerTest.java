package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Spiral grid maths — every slot must map to a unique cell. */
class GridAssignerTest {

    @Test
    void slotZeroIsTheOrigin() {
        assertArrayEquals(new int[] {0, 0}, GridAssigner.cellForSlot(0));
    }

    @Test
    void ringOneSurroundsTheOrigin() {
        final Set<String> cells = new HashSet<>();
        for (int slot = 1; slot <= 8; slot++) {
            final int[] cell = GridAssigner.cellForSlot(slot);
            assertEquals(1, Math.max(Math.abs(cell[0]), Math.abs(cell[1])),
                    "ring 1 cells must touch the origin cell");
            cells.add(cell[0] + ":" + cell[1]);
        }
        assertEquals(8, cells.size(), "ring 1 must have 8 distinct cells");
    }

    @Test
    void everySlotUpTo999IsUnique() {
        final Set<String> cells = new HashSet<>();
        for (int slot = 0; slot < 1000; slot++) {
            final int[] cell = GridAssigner.cellForSlot(slot);
            cells.add(cell[0] + ":" + cell[1]);
        }
        assertEquals(1000, cells.size(), "each slot must map to a distinct cell");
    }

    @Test
    void cellsFormRingsAroundTheOrigin() {
        // ring r has exactly 8r cells at Chebyshev distance r
        for (int ring = 1; ring <= 5; ring++) {
            final Set<String> cells = new HashSet<>();
            for (int slot = GridAssigner.ringStart(ring); slot < GridAssigner.ringStart(ring + 1); slot++) {
                final int[] cell = GridAssigner.cellForSlot(slot);
                assertEquals(ring, Math.max(Math.abs(cell[0]), Math.abs(cell[1])),
                        "ring " + ring + " cell must be at distance " + ring);
                cells.add(cell[0] + ":" + cell[1]);
            }
            assertEquals(8 * ring, cells.size(), "ring " + ring + " must have " + (8 * ring) + " cells");
        }
    }

    @Test
    void centresAreCellTimesSpacing() {
        final int[] center = GridAssigner.centerForSlot(1, 200);
        // slot 1 is cell (1, -1): first cell of ring 1
        assertArrayEquals(new int[] {200, -200}, center);
        assertArrayEquals(new int[] {0, 0}, GridAssigner.centerForSlot(0, 200));
    }

    @Test
    void adjacentSlotsNeverShareABorderWithDefaultSettings() {
        // with spacing 200 and border 100, centres are >= 200 apart so
        // two 100-wide claims can never overlap
        for (int slot = 0; slot < 200; slot++) {
            final int[] a = GridAssigner.centerForSlot(slot, 200);
            for (int other = slot + 1; other < 200; other++) {
                final int[] b = GridAssigner.centerForSlot(other, 200);
                final int distance = Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]));
                assertTrue(distance >= 200, "slots " + slot + " and " + other + " are too close");
            }
        }
    }

    @Test
    void negativeSlotIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GridAssigner.cellForSlot(-1));
    }
}
