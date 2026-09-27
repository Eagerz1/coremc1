package com.coremc.core.achievements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Slot maths for the Achievement menus. */
class AchievementsLayoutTest {

    @Test
    void theWindowMatchesTheSharedMenuLanguage() {
        assertEquals(54, AchievementsLayout.SIZE);
        assertEquals(4, AchievementsLayout.PANEL);
        assertEquals(45, AchievementsLayout.BACK);
        assertEquals(48, AchievementsLayout.PREVIOUS);
        assertEquals(49, AchievementsLayout.CLOSE);
        assertEquals(50, AchievementsLayout.NEXT);
        assertEquals(53, AchievementsLayout.EXTRA);
        assertEquals(AchievementsLayout.PREVIOUS, AchievementsLayout.PENDING);
    }

    @Test
    void theAchievementMenusLineUpWithTheCollectionMenus() {
        for (int index = 0; index < 28; index++) {
            assertEquals(com.coremc.core.collections.CollectionsLayout.contentSlot(index),
                    AchievementsLayout.contentSlot(index));
        }
        assertEquals(-1, AchievementsLayout.contentSlot(-1));
        assertEquals(-1, AchievementsLayout.contentSlot(28));
        assertEquals(-1, AchievementsLayout.contentIndexAt(0));
    }

    @Test
    void contentSlotsRoundTrip() {
        for (int index = 0; index < 28; index++) {
            final int slot = AchievementsLayout.contentSlot(index);
            assertEquals(index, AchievementsLayout.contentIndexAt(slot));
        }
    }

    @Test
    void theFrameFillsEverythingElse() {
        final Set<Integer> used = new HashSet<>();
        for (int index = 0; index < 28; index++) {
            used.add(AchievementsLayout.contentSlot(index));
        }
        used.add(AchievementsLayout.PANEL);
        used.add(AchievementsLayout.BACK);
        used.add(AchievementsLayout.PREVIOUS);
        used.add(AchievementsLayout.CLOSE);
        used.add(AchievementsLayout.NEXT);
        used.add(AchievementsLayout.EXTRA);
        for (final int slot : AchievementsLayout.frameSlots()) {
            assertFalse(used.contains(slot));
            used.add(slot);
        }
        assertEquals(54, used.size());
    }
}
