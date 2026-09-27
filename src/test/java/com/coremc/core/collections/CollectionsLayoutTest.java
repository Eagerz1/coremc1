package com.coremc.core.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Slot maths for the Collection menus. */
class CollectionsLayoutTest {

    @Test
    void theWindowIsADoubleChestWithTheStandardButtons() {
        assertEquals(54, CollectionsLayout.SIZE);
        assertEquals(4, CollectionsLayout.PANEL);
        assertEquals(45, CollectionsLayout.BACK);
        assertEquals(48, CollectionsLayout.PREVIOUS);
        assertEquals(49, CollectionsLayout.CLOSE);
        assertEquals(50, CollectionsLayout.NEXT);
        assertEquals(53, CollectionsLayout.EXTRA);
        assertEquals(CollectionsLayout.PREVIOUS, CollectionsLayout.PENDING);
    }

    @Test
    void contentSlotsRoundTripAndStayInsideTheWindow() {
        for (int index = 0; index < 28; index++) {
            final int slot = CollectionsLayout.contentSlot(index);
            assertTrue(slot > 0 && slot < 54);
            assertEquals(index, CollectionsLayout.contentIndexAt(slot));
        }
        assertEquals(-1, CollectionsLayout.contentSlot(-1));
        assertEquals(-1, CollectionsLayout.contentSlot(28));
        assertEquals(-1, CollectionsLayout.contentIndexAt(0));
        assertEquals(-1, CollectionsLayout.contentIndexAt(49));
    }

    @Test
    void tierSlotsRoundTripAndNeverCollideWithButtons() {
        final Set<Integer> seen = new HashSet<>();
        for (int index = 0; index < CollectionsLayout.MAX_TIERS; index++) {
            final int slot = CollectionsLayout.tierSlot(index);
            assertTrue(seen.add(slot), "duplicate tier slot " + slot);
            assertEquals(index, CollectionsLayout.tierIndexAt(slot));
            assertFalse(slot == CollectionsLayout.PANEL || slot == CollectionsLayout.BACK
                    || slot == CollectionsLayout.CLOSE || slot == CollectionsLayout.EXTRA);
        }
        assertEquals(14, CollectionsLayout.MAX_TIERS);
        assertEquals(-1, CollectionsLayout.tierSlot(-1));
        assertEquals(-1, CollectionsLayout.tierSlot(CollectionsLayout.MAX_TIERS));
        assertEquals(-1, CollectionsLayout.tierIndexAt(0));
    }

    @Test
    void theFrameFillsEverySlotThatIsNotContentOrAButton() {
        final Set<Integer> used = new HashSet<>();
        for (int index = 0; index < 28; index++) {
            used.add(CollectionsLayout.contentSlot(index));
        }
        used.add(CollectionsLayout.PANEL);
        used.add(CollectionsLayout.BACK);
        used.add(CollectionsLayout.PREVIOUS);
        used.add(CollectionsLayout.CLOSE);
        used.add(CollectionsLayout.NEXT);
        used.add(CollectionsLayout.EXTRA);
        for (final int slot : CollectionsLayout.frameSlots()) {
            assertFalse(used.contains(slot), "frame overlaps slot " + slot);
            used.add(slot);
        }
        assertEquals(54, used.size(), "every slot must be accounted for");
    }
}
