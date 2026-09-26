package com.coremc.core.store;

/**
 * Slot maths for the /store menus — pure, unit-tested without a
 * server. A 54-slot double chest: credits header top-centre, content
 * centred on the middle row, back/close on the bottom row.
 */
public final class StoreLayout {

    public static final int SIZE = 54;
    public static final int CREDITS_SLOT = 4;
    public static final int INFO_SLOT = 49;
    public static final int BACK_SLOT = 45;
    public static final int CLOSE_SLOT = 53;

    /** Root category buttons: Crate Keys / Lootboxes / Bundles. */
    public static final int ROOT_KEYS_SLOT = 20;
    public static final int ROOT_LOOTBOXES_SLOT = 22;
    public static final int ROOT_BUNDLES_SLOT = 24;

    private static final int CONTENT_ROW_START = 18;
    private static final int ROW_WIDTH = 9;

    private StoreLayout() {
    }

    /**
     * Centred, contiguous content slots on the middle row (row 2),
     * overflowing onto row 3 beyond nine entries.
     */
    public static int[] contentSlots(final int count) {
        final int capped = Math.max(0, Math.min(count, 18));
        final int[] slots = new int[capped];
        final int firstRow = Math.min(capped, ROW_WIDTH);
        int start = CONTENT_ROW_START + (ROW_WIDTH - firstRow) / 2;
        for (int index = 0; index < firstRow; index++) {
            slots[index] = start + index;
        }
        if (capped > ROW_WIDTH) {
            final int secondRow = capped - ROW_WIDTH;
            start = CONTENT_ROW_START + ROW_WIDTH + (ROW_WIDTH - secondRow) / 2;
            for (int index = 0; index < secondRow; index++) {
                slots[ROW_WIDTH + index] = start + index;
            }
        }
        return slots;
    }
}
