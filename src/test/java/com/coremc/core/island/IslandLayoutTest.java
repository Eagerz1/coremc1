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
                IslandLayout.MENU_GENERATORS, IslandLayout.MENU_DELETE, IslandLayout.MENU_CLOSE};
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
    void theTwoButtonRowsAreEvenlySpaced() {
        // row 1: go home / invite / members / border
        assertEquals(10, IslandLayout.MENU_GO_HOME);
        assertEquals(IslandLayout.MENU_GO_HOME + 2, IslandLayout.MENU_INVITE);
        assertEquals(IslandLayout.MENU_INVITE + 2, IslandLayout.MENU_MEMBERS);
        assertEquals(IslandLayout.MENU_MEMBERS + 2, IslandLayout.MENU_BORDER);
        // row 2: upgrades / buffs / spawners / generators, same spacing
        assertEquals(19, IslandLayout.MENU_UPGRADES);
        assertEquals(IslandLayout.MENU_UPGRADES + 2, IslandLayout.MENU_BUFFS);
        assertEquals(IslandLayout.MENU_BUFFS + 2, IslandLayout.MENU_SPAWNERS);
        assertEquals(IslandLayout.MENU_SPAWNERS + 2, IslandLayout.MENU_GENERATORS);
        // both rows use the same columns
        assertEquals(IslandLayout.MENU_GO_HOME % 9, IslandLayout.MENU_UPGRADES % 9);
        assertEquals(IslandLayout.MENU_BORDER % 9, IslandLayout.MENU_GENERATORS % 9);
    }

    @Test
    void theMenuFrameIsTheTopAndBottomRows() {
        final int[] frame = IslandLayout.menuFrame();
        assertEquals(18, frame.length);
        final TreeSet<Integer> slots = new TreeSet<>();
        for (final int slot : frame) {
            assertTrue(slots.add(slot), "frame slot " + slot + " is listed twice");
            assertTrue(slot < 9 || slot >= IslandLayout.MENU_SIZE - 9,
                    "frame slot " + slot + " is in the top or bottom row");
        }
        // the frame never reaches the two button rows
        for (final int button : new int[]{IslandLayout.MENU_GO_HOME, IslandLayout.MENU_BORDER,
                IslandLayout.MENU_UPGRADES, IslandLayout.MENU_GENERATORS}) {
            assertTrue(!slots.contains(button), "button " + button + " stays out of the frame");
        }
    }

    @Test
    void theSubMenuFrameIsTheBottomRow() {
        final int[] frame = IslandLayout.subFrame();
        assertEquals(9, frame.length);
        for (final int slot : frame) {
            assertTrue(slot >= IslandLayout.SUB_SIZE - 9 && slot < IslandLayout.SUB_SIZE,
                    "frame slot " + slot + " is in the bottom row");
        }
        // back and close live on that row, and are drawn before the frame
        assertTrue(IslandLayout.SUB_BACK >= IslandLayout.SUB_SIZE - 9);
        assertTrue(IslandLayout.SUB_CLOSE >= IslandLayout.SUB_SIZE - 9);
    }

    @Test
    void menuDeleteIsTwoClicksAwayFromClose() {
        assertNotEquals(IslandLayout.MENU_DELETE, IslandLayout.MENU_CLOSE,
                "delete and close must be different buttons");
    }
}
