package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Slot maths for the island GUIs: the island menu is a double chest,
 * every sub-menu a small chest, and no button may collide with a
 * data slot or fall outside its window.
 */
class IslandLayoutTest {

    @Test
    void islandMenuIsADoubleChest() {
        assertEquals(54, IslandLayout.MENU_SIZE);
        assertTrue(IslandLayout.MENU_SIZE % 9 == 0, "chest sizes are multiples of 9");
    }

    @Test
    void subMenusAreSmallChests() {
        assertEquals(27, IslandLayout.SUB_SIZE);
        for (final int slot : new int[]{IslandLayout.SUB_BACK, IslandLayout.SUB_CLOSE,
                IslandLayout.UPGRADE_CLAIM, IslandLayout.UPGRADE_SLOTS,
                IslandLayout.buffSlot(0), IslandLayout.buffSlot(1), IslandLayout.buffSlot(2),
                IslandLayout.MEMBERS_OWNER, IslandLayout.memberSlot(0),
                IslandLayout.memberSlot(5), IslandLayout.inviteSlot(0),
                IslandLayout.inviteSlot(7)}) {
            assertTrue(slot >= 0 && slot < IslandLayout.SUB_SIZE,
                    "slot " + slot + " must fit a small chest");
        }
    }

    @Test
    void allIslandMenuButtonsAreDistinctAndInsideTheDoubleChest() {
        final int[] buttons = {IslandLayout.MENU_INFO, IslandLayout.MENU_GO_HOME,
                IslandLayout.MENU_INVITE, IslandLayout.MENU_MEMBERS, IslandLayout.MENU_BORDER,
                IslandLayout.MENU_UPGRADES, IslandLayout.MENU_BUFFS, IslandLayout.MENU_SPAWNERS,
                IslandLayout.MENU_DELETE, IslandLayout.MENU_CLOSE};
        final TreeSet<Integer> seen = new TreeSet<>();
        for (final int button : buttons) {
            assertTrue(button >= 0 && button < IslandLayout.MENU_SIZE,
                    "button " + button + " fits the double chest");
            assertTrue(seen.add(button), "button " + button + " collides with another");
        }
        assertEquals(buttons.length, seen.size());
    }

    @Test
    void buffSlotsAreTwoApart() {
        assertEquals(11, IslandLayout.buffSlot(0));
        assertEquals(13, IslandLayout.buffSlot(1));
        assertEquals(15, IslandLayout.buffSlot(2));
    }

    @Test
    void memberAndInviteSlotsAreConsecutive() {
        for (int i = 0; i < IslandLayout.MEMBERS_MAX; i++) {
            assertEquals(IslandLayout.MEMBERS_FIRST + i, IslandLayout.memberSlot(i));
        }
        for (int i = 0; i < IslandLayout.INVITE_MAX; i++) {
            assertEquals(IslandLayout.INVITE_FIRST + i, IslandLayout.inviteSlot(i));
        }
        // member slots never collide with back/close
        assertNotEquals(IslandLayout.SUB_BACK, IslandLayout.memberSlot(IslandLayout.MEMBERS_MAX - 1));
    }

    @Test
    void withinEachSubMenuTheDataSlotsAreDistinct() {
        // upgrades menu: two upgrade buttons
        final TreeSet<Integer> upgrades = new TreeSet<>();
        assertTrue(upgrades.add(IslandLayout.UPGRADE_CLAIM));
        assertTrue(upgrades.add(IslandLayout.UPGRADE_SLOTS));
        // buffs menu: three buff buttons
        final TreeSet<Integer> buffs = new TreeSet<>();
        for (int i = 0; i < 3; i++) {
            assertTrue(buffs.add(IslandLayout.buffSlot(i)), "buff slot " + i + " is distinct");
        }
    }

    @Test
    void menuDeleteIsTwoClicksAwayFromClose() {
        assertNotEquals(IslandLayout.MENU_DELETE, IslandLayout.MENU_CLOSE,
                "delete and close must be different buttons");
    }
}
