package com.coremc.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The shared paged-menu grid every new CoreMC menu is built on. */
class GuiGridTest {

    @Test
    void theGridIsAFramedDoubleChestWithTwentyEightContentSlots() {
        assertEquals(54, GuiGrid.SIZE);
        assertEquals(28, GuiGrid.PER_PAGE);
        assertEquals(28, GuiGrid.contentSlots().length);
        for (final int slot : GuiGrid.contentSlots()) {
            assertTrue(slot > 8 && slot < 44, "content slot " + slot + " is outside the frame");
        }
    }

    @Test
    void slotsAndIndexesRoundTrip() {
        for (int index = 0; index < GuiGrid.PER_PAGE; index++) {
            assertEquals(index, GuiGrid.indexAt(GuiGrid.slot(index)));
        }
        assertEquals(-1, GuiGrid.slot(-1));
        assertEquals(-1, GuiGrid.slot(GuiGrid.PER_PAGE));
        assertEquals(-1, GuiGrid.indexAt(0));
        assertEquals(-1, GuiGrid.indexAt(53));
    }

    @Test
    void contentSlotsAreACopySoCallersCannotCorruptTheGrid() {
        final int[] slots = GuiGrid.contentSlots();
        slots[0] = -99;
        assertEquals(10, GuiGrid.contentSlots()[0]);
    }

    @Test
    void pagingIsAlwaysAtLeastOnePageAndNeverOutOfRange() {
        assertEquals(1, GuiGrid.pages(0));
        assertEquals(1, GuiGrid.pages(28));
        assertEquals(2, GuiGrid.pages(29));
        assertEquals(4, GuiGrid.pages(100));
        assertEquals(1, GuiGrid.pages(-5));
        assertEquals(10, GuiGrid.pages(10, 1));
        assertEquals(10, GuiGrid.pages(10, 0), "a zero page size must not divide by zero");

        assertEquals(0, GuiGrid.clampPage(-3, 100));
        assertEquals(3, GuiGrid.clampPage(9, 100));
        assertEquals(0, GuiGrid.clampPage(5, 0));
        assertEquals(1, GuiGrid.clampPage(1, 100));
        assertEquals(0, GuiGrid.offset(0, 28));
        assertEquals(28, GuiGrid.offset(1, 28));
        assertEquals(0, GuiGrid.offset(-2, 28));
        assertEquals(3, GuiGrid.offset(3, 0));
    }

    @Test
    void theFrameCoversExactlyWhatTheContentAndButtonsDoNot() {
        final Set<Integer> used = new HashSet<>();
        for (final int slot : GuiGrid.contentSlots()) {
            used.add(slot);
        }
        used.add(GuiGrid.PANEL);
        used.add(GuiGrid.BACK);
        used.add(GuiGrid.PREVIOUS);
        used.add(GuiGrid.CLOSE);
        used.add(GuiGrid.NEXT);
        used.add(GuiGrid.EXTRA);
        for (final int slot : GuiGrid.frameSlots()) {
            assertFalse(used.contains(slot));
            used.add(slot);
        }
        assertEquals(GuiGrid.SIZE, used.size());
    }
}
