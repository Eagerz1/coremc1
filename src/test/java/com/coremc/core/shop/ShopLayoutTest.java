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
        assertEquals(1, ShopLayout.pageCount(18));
        assertEquals(2, ShopLayout.pageCount(19));
        assertEquals(2, ShopLayout.pageCount(36));
        assertEquals(3, ShopLayout.pageCount(37));
    }

    @Test
    void pageItemsAreSlicedInSlotOrder() {
        final List<ShopItem> page1 = ShopLayout.pageItems(items(20), 0);
        final List<ShopItem> page2 = ShopLayout.pageItems(items(20), 1);
        assertEquals(18, page1.size());
        assertEquals(2, page2.size());
        // Out-of-range pages clamp to the last page.
        assertEquals(2, ShopLayout.pageItems(items(20), 99).size());
    }

    @Test
    void itemSlotsMapToListIndexes() {
        // Page 0 of 20 items: slots 0-17 map to indexes 0-17.
        assertEquals(0, ShopLayout.itemIndexForSlot(20, 0, 0));
        assertEquals(17, ShopLayout.itemIndexForSlot(20, 0, 17));
        // Page 1: slots 0-1 map to indexes 18-19, slots 2+ are empty.
        assertEquals(18, ShopLayout.itemIndexForSlot(20, 1, 0));
        assertEquals(19, ShopLayout.itemIndexForSlot(20, 1, 1));
        assertEquals(-1, ShopLayout.itemIndexForSlot(20, 1, 2));
        // Nav slots are never item slots.
        assertEquals(-1, ShopLayout.itemIndexForSlot(20, 0, 18));
        assertEquals(-1, ShopLayout.itemIndexForSlot(20, 0, 26));
        assertFalse(ShopLayout.isItemSlot(18));
        assertTrue(ShopLayout.isItemSlot(17));
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
        assertEquals(18, ShopLayout.SLOT_BACK);
        assertEquals(20, ShopLayout.SLOT_PREVIOUS);
        assertEquals(22, ShopLayout.SLOT_PAGE);
        assertEquals(24, ShopLayout.SLOT_NEXT);
        assertEquals(26, ShopLayout.SLOT_CLOSE);
        assertEquals(4, ShopLayout.ROOT_BALANCE_SLOT);
        assertEquals(22, ShopLayout.ROOT_CLOSE_SLOT);
        assertEquals(27, ShopLayout.SIZE);
        assertEquals(18, ShopLayout.ITEMS_PER_PAGE);
    }
}
