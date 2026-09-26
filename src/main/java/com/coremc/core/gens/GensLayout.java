package com.coremc.core.gens;

/**
 * Pure slot maths for the generator GUIs, so the GUI code stays dumb
 * and the layout is unit-testable.
 *
 * <pre>
 * /gens menu (double chest, 54):
 *   [4]  your generator panel
 *   generators fill a centred 5-wide band:
 *     [11..15] tiers 1–5
 *     [20..24] tiers 6–10
 *     [29..33] tiers 11–15
 *   [45] back to the island menu   [49] close
 *
 * Generator management (small chest, 27):
 *   [11] upgrade   [13] the generator   [15] pick up   [22] close
 * </pre>
 */
public final class GensLayout {

    /** The /gens menu is a double chest. */
    public static final int MENU_SIZE = 54;
    /** The management window is a small chest. */
    public static final int MANAGE_SIZE = 27;

    public static final int MENU_INFO = 4;
    public static final int MENU_BACK = 45;
    public static final int MENU_CLOSE = 49;

    public static final int MANAGE_UPGRADE = 11;
    public static final int MANAGE_INFO = 13;
    public static final int MANAGE_PICKUP = 15;
    public static final int MANAGE_CLOSE = 22;

    /** The centred band of generator slots, in progression order. */
    private static final int[] GEN_SLOTS = {
            11, 12, 13, 14, 15,
            20, 21, 22, 23, 24,
            29, 30, 31, 32, 33
    };

    /** How many generators the menu can show. */
    public static final int MAX_GENERATORS = GEN_SLOTS.length;

    private GensLayout() {
    }

    /** Slot of generator {@code index} (0-based, by tier), or -1. */
    public static int genSlot(final int index) {
        if (index < 0 || index >= GEN_SLOTS.length) {
            return -1;
        }
        return GEN_SLOTS[index];
    }

    /** Decodes a menu slot back into a generator index, or -1. */
    public static int genIndexAt(final int slot) {
        for (int index = 0; index < GEN_SLOTS.length; index++) {
            if (GEN_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }
}
