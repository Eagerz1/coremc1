package com.coremc.core.island;

/**
 * Pure slot maths for the island menu GUIs, so the GUI code stays
 * dumb and the layout is unit-testable.
 *
 * <pre>
 * Island menu (double chest, 54):
 *   [4] info        [10] go home    [12] invite   [14] members
 *   [16] border     [20] upgrades   [24] buffs    [31] spawners
 *   [45] delete     [49] close      everything else filler.
 * Sub-menus (small chest, 27):
 *   Upgrades: [11] claim size, [15] member slots, [18] back, [26] close.
 *   Buffs:    [11], [13], [15] buffs,             [18] back, [26] close.
 *   Members:  [10] owner head, [12..17] members,  [18] back, [26] close.
 *   Invite:   [10..17] invite candidates,         [18] back, [26] close.
 * </pre>
 */
public final class IslandLayout {

    /** The island menu is a double chest. */
    public static final int MENU_SIZE = 54;
    /** Every sub-menu is a small chest. */
    public static final int SUB_SIZE = 27;

    public static final int MENU_INFO = 4;
    public static final int MENU_GO_HOME = 10;
    public static final int MENU_INVITE = 12;
    public static final int MENU_MEMBERS = 14;
    public static final int MENU_BORDER = 16;
    public static final int MENU_UPGRADES = 20;
    public static final int MENU_BUFFS = 24;
    public static final int MENU_SPAWNERS = 31;
    public static final int MENU_DELETE = 45;
    public static final int MENU_CLOSE = 49;

    public static final int SUB_BACK = 18;
    public static final int SUB_CLOSE = 26;

    public static final int UPGRADE_CLAIM = 11;
    public static final int UPGRADE_SLOTS = 15;

    public static final int BUFF_FIRST = 11;
    /** Buffs sit two slots apart: 11, 13, 15. */
    public static int buffSlot(final int ordinal) {
        return BUFF_FIRST + ordinal * 2;
    }

    public static final int MEMBERS_OWNER = 10;
    public static final int MEMBERS_FIRST = 12;
    public static final int MEMBERS_MAX = 6;
    public static int memberSlot(final int ordinal) {
        return MEMBERS_FIRST + ordinal;
    }

    public static final int INVITE_FIRST = 10;
    public static final int INVITE_MAX = 8;
    public static int inviteSlot(final int ordinal) {
        return INVITE_FIRST + ordinal;
    }

    private IslandLayout() {
    }
}
