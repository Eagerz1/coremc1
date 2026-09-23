package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Island top layout maths: picker and board slot mapping plus the
 * season gift card reward tiers.
 */
final class IsTopLayoutTest {

    @Test
    void rewardTiersMatchTheSpec() {
        assertEquals(List.of(100.0, 75.0, 50.0, 30.0, 25.0),
                IsTopLayout.rewards(IslandTop.Category.SOLOS));
        assertEquals(List.of(100.0, 75.0, 50.0),
                IsTopLayout.rewards(IslandTop.Category.DUOS));
        assertEquals(List.of(100.0, 75.0, 50.0, 35.0, 25.0),
                IsTopLayout.rewards(IslandTop.Category.TEAMS));
    }

    @Test
    void rewardForCoversRewardedPlacesOnly() {
        assertEquals(100.0, IsTopLayout.rewardFor(IslandTop.Category.SOLOS, 1));
        assertEquals(30.0, IsTopLayout.rewardFor(IslandTop.Category.SOLOS, 4));
        assertEquals(25.0, IsTopLayout.rewardFor(IslandTop.Category.SOLOS, 5));
        assertEquals(0.0, IsTopLayout.rewardFor(IslandTop.Category.SOLOS, 6));
        assertEquals(0.0, IsTopLayout.rewardFor(IslandTop.Category.SOLOS, 0));
        assertEquals(50.0, IsTopLayout.rewardFor(IslandTop.Category.DUOS, 3));
        assertEquals(0.0, IsTopLayout.rewardFor(IslandTop.Category.DUOS, 4));
        assertEquals(35.0, IsTopLayout.rewardFor(IslandTop.Category.TEAMS, 4));
    }

    @Test
    void rewardedPlaces() {
        assertEquals(5, IsTopLayout.rewardedPlaces(IslandTop.Category.SOLOS));
        assertEquals(3, IsTopLayout.rewardedPlaces(IslandTop.Category.DUOS));
        assertEquals(5, IsTopLayout.rewardedPlaces(IslandTop.Category.TEAMS));
    }

    @Test
    void boardSlotsLayTenRanksInTwoRows() {
        assertEquals(10, IsTopLayout.boardSlot(1));
        assertEquals(14, IsTopLayout.boardSlot(5));
        assertEquals(19, IsTopLayout.boardSlot(6));
        assertEquals(23, IsTopLayout.boardSlot(10));
        assertEquals(-1, IsTopLayout.boardSlot(0));
        assertEquals(-1, IsTopLayout.boardSlot(11));
    }

    @Test
    void pickerSlotsMapToCategories() {
        assertEquals(IsTopLayout.PICKER_SOLOS, IsTopLayout.pickerSlot(IslandTop.Category.SOLOS));
        assertEquals(IsTopLayout.PICKER_DUOS, IsTopLayout.pickerSlot(IslandTop.Category.DUOS));
        assertEquals(IsTopLayout.PICKER_TEAMS, IsTopLayout.pickerSlot(IslandTop.Category.TEAMS));
        assertEquals(IslandTop.Category.SOLOS, IsTopLayout.pickerCategoryAt(IsTopLayout.PICKER_SOLOS));
        assertEquals(IslandTop.Category.DUOS, IsTopLayout.pickerCategoryAt(IsTopLayout.PICKER_DUOS));
        assertEquals(IslandTop.Category.TEAMS, IsTopLayout.pickerCategoryAt(IsTopLayout.PICKER_TEAMS));
        assertNull(IsTopLayout.pickerCategoryAt(IsTopLayout.PICKER_INFO));
        assertNull(IsTopLayout.pickerCategoryAt(IsTopLayout.PICKER_CLOSE));
        assertNull(IsTopLayout.pickerCategoryAt(0));
    }

    @Test
    void windowSizes() {
        assertEquals(27, IsTopLayout.PICKER_SIZE);
        assertEquals(54, IsTopLayout.BOARD_SIZE);
        assertEquals(10, IsTopLayout.BOARD_ENTRIES);
    }
}
