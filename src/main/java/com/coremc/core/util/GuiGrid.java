package com.coremc.core.util;

/**
 * The shared content grid used by the paged CoreMC menus (Collections,
 * Achievements): a framed double chest with a 28-slot inner area and
 * the standard navigation row along the bottom.
 *
 * <pre>
 *  0 .. 8   frame (row 0, with the profile panel at 4)
 *  9,17     frame columns
 * 10 .. 16  content row 1
 * 19 .. 25  content row 2
 * 28 .. 34  content row 3
 * 37 .. 43  content row 4
 * 45 back   48 previous   49 close   50 next    53 frame
 * </pre>
 *
 * <p>Pure slot and page maths, so the layout is unit-tested without a
 * server and every paged menu in the plugin lines up pixel for pixel.</p>
 */
public final class GuiGrid {

    /** Every paged menu is a double chest. */
    public static final int SIZE = 54;

    /** The profile / summary panel. */
    public static final int PANEL = 4;
    /** Back to the previous menu. */
    public static final int BACK = 45;
    /** Previous page. */
    public static final int PREVIOUS = 48;
    /** Close. */
    public static final int CLOSE = 49;
    /** Next page. */
    public static final int NEXT = 50;
    /** Context button (recipes, pending rewards, secrets). */
    public static final int EXTRA = 53;

    private static final int[] CONTENT = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    /** How many entries fit on one page. */
    public static final int PER_PAGE = CONTENT.length;

    private GuiGrid() {
    }

    /** Slot of content position {@code index} (0-based), or -1. */
    public static int slot(final int index) {
        if (index < 0 || index >= CONTENT.length) {
            return -1;
        }
        return CONTENT[index];
    }

    /** Content position of a slot, or -1 when it is not a content slot. */
    public static int indexAt(final int slot) {
        for (int index = 0; index < CONTENT.length; index++) {
            if (CONTENT[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    /** Every content slot, in order. */
    public static int[] contentSlots() {
        return CONTENT.clone();
    }

    /** How many pages {@code total} entries need (always at least one). */
    public static int pages(final int total) {
        return pages(total, PER_PAGE);
    }

    /** How many pages {@code total} entries need at a custom page size. */
    public static int pages(final int total, final int perPage) {
        final int size = Math.max(1, perPage);
        if (total <= 0) {
            return 1;
        }
        return (total + size - 1) / size;
    }

    /** Clamps a page number into range (pages are 0-based). */
    public static int clampPage(final int page, final int total) {
        return clampPage(page, total, PER_PAGE);
    }

    /** Clamps a page number into range at a custom page size. */
    public static int clampPage(final int page, final int total, final int perPage) {
        return Math.max(0, Math.min(page, pages(total, perPage) - 1));
    }

    /** Index of the first entry on a page. */
    public static int offset(final int page, final int perPage) {
        return Math.max(0, page) * Math.max(1, perPage);
    }

    /** The frame slots of a paged menu (everything that is not content or a button). */
    public static int[] frameSlots() {
        final boolean[] used = new boolean[SIZE];
        for (final int slot : CONTENT) {
            used[slot] = true;
        }
        used[PANEL] = true;
        used[BACK] = true;
        used[PREVIOUS] = true;
        used[CLOSE] = true;
        used[NEXT] = true;
        used[EXTRA] = true;
        int count = 0;
        for (final boolean taken : used) {
            if (!taken) {
                count++;
            }
        }
        final int[] frame = new int[count];
        int cursor = 0;
        for (int slot = 0; slot < SIZE; slot++) {
            if (!used[slot]) {
                frame[cursor++] = slot;
            }
        }
        return frame;
    }
}
