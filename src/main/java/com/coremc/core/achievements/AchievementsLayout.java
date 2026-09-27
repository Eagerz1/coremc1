package com.coremc.core.achievements;

import com.coremc.core.util.GuiGrid;

/**
 * Pure slot maths for the Achievement menus.
 *
 * <pre>
 * /achievements root (54)   categories in the shared 28-slot grid
 *   [4] your points panel   [45] island menu   [49] close
 *   [48] held rewards       [53] secrets
 *
 * category page (54)        achievements in the shared grid, paged
 *   [4] category panel  [45] back  [48] prev  [49] close  [50] next
 *
 * secrets page (54)         every secret, revealed only once earned
 * </pre>
 */
public final class AchievementsLayout {

    public static final int SIZE = GuiGrid.SIZE;
    public static final int PANEL = GuiGrid.PANEL;
    public static final int BACK = GuiGrid.BACK;
    public static final int PREVIOUS = GuiGrid.PREVIOUS;
    public static final int CLOSE = GuiGrid.CLOSE;
    public static final int NEXT = GuiGrid.NEXT;
    /** Secrets on the root, held rewards on the paged views. */
    public static final int EXTRA = GuiGrid.EXTRA;
    /** Held-rewards button on the root menu. */
    public static final int PENDING = GuiGrid.PREVIOUS;

    private AchievementsLayout() {
    }

    /** Slot of content position {@code index}. */
    public static int contentSlot(final int index) {
        return GuiGrid.slot(index);
    }

    /** Content position of a slot, or -1. */
    public static int contentIndexAt(final int slot) {
        return GuiGrid.indexAt(slot);
    }

    /** Frame slots for an Achievement window. */
    public static int[] frameSlots() {
        return GuiGrid.frameSlots();
    }
}
