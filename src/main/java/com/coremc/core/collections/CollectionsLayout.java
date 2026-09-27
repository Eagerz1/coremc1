package com.coremc.core.collections;

import com.coremc.core.util.GuiGrid;

/**
 * Pure slot maths for the Collection menus, so the GUI code stays
 * dumb and every layout rule is unit-tested.
 *
 * <pre>
 * /collections root (54)   categories in the shared 28-slot grid
 *   [4] your progress panel   [45] island menu   [49] close
 *   [48] pending rewards      [53] recipes
 *
 * category page (54)       entries in the shared grid, paged
 *   [4] category panel  [45] back  [48] prev  [49] close  [50] next
 *
 * entry detail (54)        milestone tiers
 *   [4] the collection   [19..25] + [28..34] tiers I–XIV
 *   [45] back to the category   [49] close
 * </pre>
 */
public final class CollectionsLayout {

    /** Every Collection window is a double chest. */
    public static final int SIZE = GuiGrid.SIZE;

    public static final int PANEL = GuiGrid.PANEL;
    public static final int BACK = GuiGrid.BACK;
    public static final int PREVIOUS = GuiGrid.PREVIOUS;
    public static final int CLOSE = GuiGrid.CLOSE;
    public static final int NEXT = GuiGrid.NEXT;
    /** Recipes book on the root, pending rewards on the other pages. */
    public static final int EXTRA = GuiGrid.EXTRA;
    /** Pending-reward button on the root menu. */
    public static final int PENDING = GuiGrid.PREVIOUS;

    private static final int[] TIER_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    /** How many milestone tiers the detail view can show. */
    public static final int MAX_TIERS = TIER_SLOTS.length;

    private CollectionsLayout() {
    }

    /** Slot of content position {@code index}. */
    public static int contentSlot(final int index) {
        return GuiGrid.slot(index);
    }

    /** Content position of a slot, or -1. */
    public static int contentIndexAt(final int slot) {
        return GuiGrid.indexAt(slot);
    }

    /** Slot of milestone tier {@code index} (0-based), or -1. */
    public static int tierSlot(final int index) {
        if (index < 0 || index >= TIER_SLOTS.length) {
            return -1;
        }
        return TIER_SLOTS[index];
    }

    /** Milestone tier at a slot, or -1. */
    public static int tierIndexAt(final int slot) {
        for (int index = 0; index < TIER_SLOTS.length; index++) {
            if (TIER_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    /** Frame slots for a Collection window. */
    public static int[] frameSlots() {
        return GuiGrid.frameSlots();
    }
}
