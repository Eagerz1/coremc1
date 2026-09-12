package com.coremc.core.island;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GridAssignerTest {

    private static final String WORLD = "world";

    @Test
    void firstCellIsOrigin() {
        final int[] cell = GridAssigner.nextFreeCell(new HashSet<>(), WORLD);
        assertArrayEquals(new int[] {0, 0}, cell);
    }

    @Test
    void spiralOrderIsDeterministic() {
        final Set<String> used = new HashSet<>();
        final String[][] expected = {
            {"0", "0"}, {"1", "0"}, {"1", "1"}, {"0", "1"}, {"-1", "1"},
            {"-1", "0"}, {"-1", "-1"}, {"0", "-1"}, {"1", "-1"}, {"2", "-1"}
        };
        for (final String[] pair : expected) {
            final int[] cell = GridAssigner.nextFreeCell(used, WORLD);
            assertArrayEquals(new int[] {Integer.parseInt(pair[0]), Integer.parseInt(pair[1])}, cell);
            used.add(GridAssigner.key(cell[0], cell[1], WORLD));
        }
    }

    @Test
    void skipsOccupiedCells() {
        final Set<String> used = Set.of(
                GridAssigner.key(0, 0, WORLD),
                GridAssigner.key(1, 0, WORLD));
        final int[] cell = GridAssigner.nextFreeCell(used, WORLD);
        assertArrayEquals(new int[] {1, 1}, cell);
    }

    @Test
    void cellsAreScopedPerWorld() {
        final Set<String> used = Set.of(GridAssigner.key(0, 0, "otherworld"));
        final int[] cell = GridAssigner.nextFreeCell(used, WORLD);
        assertArrayEquals(new int[] {0, 0}, cell);
    }

    @Test
    void keysIncludeWorld() {
        assertNotEquals(GridAssigner.key(1, 1, "a"), GridAssigner.key(1, 1, "b"));
    }
}
