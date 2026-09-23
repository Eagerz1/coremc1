package com.coremc.core.shop;

import java.util.List;

/**
 * Pure layout and pagination maths for the shop GUIs, so the GUI code
 * can stay dumb and the layout is testable.
 *
 * <pre>
 * Root menu (small chest, 27):
 *             [ 4 ] = balance, [10..16] = up to seven sections,
 *             [22] = close, everything else filler.
 * Section (double chest, 54): [0..35] = items (36 per page),
 *             [45] back, [46] previous page, [49] page indicator,
 *             [52] next page, [53] close, everything else filler.
 * </pre>
 */
public final class ShopLayout {

    /** Root menu size: a small chest (three rows). */
    public static final int SIZE = 27;

    /** Section page size: a double chest (six rows). */
    public static final int SECTION_SIZE = 54;

    /** Item slots per section page (top four rows). */
    public static final int ITEMS_PER_PAGE = 36;

    public static final int ROOT_BALANCE_SLOT = 4;
    public static final int ROOT_FIRST_SECTION_SLOT = 10;
    public static final int ROOT_CLOSE_SLOT = 22;

    public static final int SLOT_BACK = 45;
    public static final int SLOT_PREVIOUS = 46;
    public static final int SLOT_PAGE = 49;
    public static final int SLOT_NEXT = 52;
    public static final int SLOT_CLOSE = 53;

    private ShopLayout() {
    }

    /** Number of pages a section with {@code itemCount} items needs (at least 1). */
    public static int pageCount(final int itemCount) {
        return Math.max(1, (itemCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
    }

    /** First/last item indexes shown on {@code page} (0-based), clamped to the list. */
    public static int[] pageRange(final int itemCount, final int page) {
        final int from = Math.min(page, pageCount(itemCount) - 1) * ITEMS_PER_PAGE;
        final int to = Math.min(from + ITEMS_PER_PAGE, itemCount);
        return new int[]{from, to};
    }

    /** Items shown on {@code page}, in slot order. */
    public static List<ShopItem> pageItems(final List<ShopItem> items, final int page) {
        final int[] range = pageRange(items.size(), page);
        return items.subList(range[0], range[1]);
    }

    /** True when {@code slot} shows a shop item on a section page. */
    public static boolean isItemSlot(final int slot) {
        return slot >= 0 && slot < ITEMS_PER_PAGE;
    }

    /** Item list index for a section-page slot, or -1 when the slot is empty on this page. */
    public static int itemIndexForSlot(final int itemCount, final int page, final int slot) {
        if (!isItemSlot(slot)) {
            return -1;
        }
        final int[] range = pageRange(itemCount, page);
        final int index = range[0] + slot;
        return index < range[1] ? index : -1;
    }

    /** Root-menu slot for the section at {@code ordinal}, or -1 when it has no slot. */
    public static int sectionSlot(final int ordinal) {
        final int slot = ROOT_FIRST_SECTION_SLOT + ordinal;
        return slot < ROOT_FIRST_SECTION_SLOT + ShopConfig.MAX_SECTIONS ? slot : -1;
    }

    /** Section ordinal for a root-menu slot, or -1 when the slot is not a section slot. */
    public static int sectionOrdinalForSlot(final int slot) {
        final int ordinal = slot - ROOT_FIRST_SECTION_SLOT;
        return (ordinal >= 0 && ordinal < ShopConfig.MAX_SECTIONS) ? ordinal : -1;
    }
}
