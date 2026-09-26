package com.coremc.core.gens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Slot maths for the generator GUIs: the /gens menu is a double
 * chest, the management window a small chest, and no button may
 * collide with a generator slot or fall outside its window.
 */
class GensLayoutTest {

    @Test
    void windowsAreChestSized() {
        assertEquals(54, GensLayout.MENU_SIZE);
        assertEquals(27, GensLayout.MANAGE_SIZE);
        assertEquals(0, GensLayout.MENU_SIZE % 9);
        assertEquals(0, GensLayout.MANAGE_SIZE % 9);
    }

    @Test
    void generatorSlotsAreDistinctAndInsideTheMenu() {
        final TreeSet<Integer> seen = new TreeSet<>();
        for (int index = 0; index < GensLayout.MAX_GENERATORS; index++) {
            final int slot = GensLayout.genSlot(index);
            assertTrue(slot >= 0 && slot < GensLayout.MENU_SIZE, "slot " + slot + " fits");
            assertTrue(seen.add(slot), "slot " + slot + " is used twice");
        }
        assertEquals(GensLayout.MAX_GENERATORS, seen.size());
    }

    @Test
    void generatorSlotsNeverCollideWithButtons() {
        for (int index = 0; index < GensLayout.MAX_GENERATORS; index++) {
            final int slot = GensLayout.genSlot(index);
            assertTrue(slot != GensLayout.MENU_INFO, "info button stays free");
            assertTrue(slot != GensLayout.MENU_BACK, "back button stays free");
            assertTrue(slot != GensLayout.MENU_CLOSE, "close button stays free");
        }
    }

    @Test
    void slotAndIndexAreInverse() {
        for (int index = 0; index < GensLayout.MAX_GENERATORS; index++) {
            assertEquals(index, GensLayout.genIndexAt(GensLayout.genSlot(index)));
        }
        assertEquals(-1, GensLayout.genIndexAt(GensLayout.MENU_CLOSE));
        assertEquals(-1, GensLayout.genIndexAt(0));
        assertEquals(-1, GensLayout.genSlot(-1));
        assertEquals(-1, GensLayout.genSlot(GensLayout.MAX_GENERATORS));
    }

    @Test
    void theMenuHoldsTheWholeShippedProgression() {
        assertTrue(GensLayout.MAX_GENERATORS >= 10,
                "the shipped ladder has ten generators");
    }

    @Test
    void managementButtonsAreDistinctAndInsideTheWindow() {
        final int[] buttons = {GensLayout.MANAGE_UPGRADE, GensLayout.MANAGE_INFO,
                GensLayout.MANAGE_PICKUP, GensLayout.MANAGE_CLOSE};
        final TreeSet<Integer> seen = new TreeSet<>();
        for (final int button : buttons) {
            assertTrue(button >= 0 && button < GensLayout.MANAGE_SIZE,
                    "button " + button + " fits the small chest");
            assertTrue(seen.add(button), "button " + button + " collides");
        }
        assertEquals(buttons.length, seen.size());
    }
}
