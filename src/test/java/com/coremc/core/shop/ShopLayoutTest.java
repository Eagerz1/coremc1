package com.coremc.core.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** Pagination and slot maths for the 27-slot shop GUIs. */
class ShopLayoutTest {

    private static List<ShopItem> items(final int count) {
        final List<ShopItem> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new ShopItem(Material.COBBLESTONE, 1, 0.25));
        }
        return list;
    }

    @Test
    void pageCountRoundsUpAndNeverZero() {
        assertEquals(1, ShopLayout.pageCount(0));
        assertEquals(1, ShopLayout.pageCount(1));
        assertEquals(1, ShopLayout.pageCount(36));
        assertEquals(2, ShopLayout.pageCount(37));
        assertEquals(2, ShopLayout.pageCount(72));
        assertEquals(3, ShopLayout.pageCount(73));
    }

    @Test
    void pageItemsAreSlicedInSlotOrder() {
        final List<ShopItem> page1 = ShopLayout.pageItems(items(40), 0);
        final List<ShopItem> page2 = ShopLayout.pageItems(items(40), 1);
        assertEquals(36, page1.size());
        assertEquals(4, page2.size());
        // Out-of-range pages clamp to the last page.
        assertEquals(4, ShopLayout.pageItems(items(40), 99).size());
    }

    @Test
    void itemSlotsMapToListIndexes() {
        // Page 0 of 40 items: slots 0-35 map to indexes 0-35.
        assertEquals(0, ShopLayout.itemIndexForSlot(40, 0, 0));
        assertEquals(35, ShopLayout.itemIndexForSlot(40, 0, 35));
        // Page 1: slots 0-3 map to indexes 36-39, slots 4+ are empty.
        assertEquals(36, ShopLayout.itemIndexForSlot(40, 1, 0));
        assertEquals(39, ShopLayout.itemIndexForSlot(40, 1, 3));
        assertEquals(-1, ShopLayout.itemIndexForSlot(40, 1, 4));
        // Nav slots are never item slots.
        assertEquals(-1, ShopLayout.itemIndexForSlot(40, 0, 36));
        assertEquals(-1, ShopLayout.itemIndexForSlot(40, 0, 45));
        assertEquals(-1, ShopLayout.itemIndexForSlot(40, 0, 53));
        assertFalse(ShopLayout.isItemSlot(36));
        assertTrue(ShopLayout.isItemSlot(35));
    }

    @Test
    void rootSectionSlotsRoundTrip() {
        for (int ordinal = 0; ordinal < 7; ordinal++) {
            assertEquals(ordinal, ShopLayout.sectionOrdinalForSlot(ShopLayout.sectionSlot(ordinal)));
        }
        assertEquals(-1, ShopLayout.sectionSlot(7));
        assertEquals(-1, ShopLayout.sectionOrdinalForSlot(9));
        assertEquals(-1, ShopLayout.sectionOrdinalForSlot(17));
        assertEquals(-1, ShopLayout.sectionOrdinalForSlot(22));
    }

    @Test
    void navSlotsAreWhereTheGuiPutsThem() {
        // Sections are double chests with the nav row at the bottom.
        assertEquals(54, ShopLayout.SECTION_SIZE);
        assertEquals(45, ShopLayout.SLOT_BACK);
        assertEquals(46, ShopLayout.SLOT_PREVIOUS);
        assertEquals(49, ShopLayout.SLOT_PAGE);
        assertEquals(52, ShopLayout.SLOT_NEXT);
        assertEquals(53, ShopLayout.SLOT_CLOSE);
        // The root menu stays a small chest.
        assertEquals(4, ShopLayout.ROOT_BALANCE_SLOT);
        assertEquals(22, ShopLayout.ROOT_CLOSE_SLOT);
        assertEquals(27, ShopLayout.SIZE);
        assertEquals(36, ShopLayout.ITEMS_PER_PAGE);
    }

    @Test
    void groupSlotsRoundTripAcrossTwoRowsOfSeven() {
        for (int ordinal = 0; ordinal < 14; ordinal++) {
            assertEquals(ordinal, ShopLayout.groupOrdinalForSlot(ShopLayout.groupSlot(ordinal)));
        }
        // Two rows of seven, skipping the row edges.
        assertEquals(10, ShopLayout.groupSlot(0));
        assertEquals(16, ShopLayout.groupSlot(6));
        assertEquals(19, ShopLayout.groupSlot(7));
        assertEquals(25, ShopLayout.groupSlot(13));
        // There is no fifteenth picker slot.
        assertEquals(-1, ShopLayout.groupSlot(14));
        assertEquals(-1, ShopLayout.groupSlot(-1));
    }

    @Test
    void pickerGroupSlotsNeverCollideWithNavOrRootSlots() {
        // Picker slots live in rows two and three; the nav row and the
        // root-menu slots must never map to a group.
        for (final int slot : new int[]{45, 46, 49, 52, 53, 0, 9, 17, 18, 26, 27, 53}) {
            assertEquals(-1, ShopLayout.groupOrdinalForSlot(slot), "slot " + slot);
        }
    }
}
