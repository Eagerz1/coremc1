package com.coremc.core.spawner;

import java.util.List;

/**
 * Pure slot maths for the spawner menu GUI (double chest, 54):
 *
 * <pre>
 * [0] guide book                    [4] spawner luck
 * mobs flow DOWN three columns (5 per column, 15 total):
 * [10,19,28,37,46]  column 1: mobs 0–4
 * [11,20,29,38,47]  column 2: mobs 5–9
 * [12,21,30,39,48]  column 3: mobs 10–14
 * [52] close                       everything else filler.
 * </pre>
 *
 * <p>Column-major: mob {@code i} sits at row {@code i % 5}, column
 * {@code i / 5} — the first mob of the first group is always slot 10,
 * and a group's mobs stack vertically, one group per column band.</p>
 */
public final class SpawnerMenuLayout {

    /** The spawner menu is a double chest. */
    public static final int SIZE = 54;

    public static final int GUIDE = 0;
    public static final int LUCK = 4;
    public static final int CLOSE = 52;

    /** Rows of mob slots (one per group row band). */
    public static final int MOB_ROWS = 5;
    /** Columns of mob slots. */
    public static final int MOB_COLUMNS = 3;
    /** Most mobs the menu can show. */
    public static final int MAX_MOBS = MOB_ROWS * MOB_COLUMNS;

    private static final int FIRST_MOB_SLOT = 10;

    /** Slot of mob {@code flatIndex} (0-based across all groups), or -1. */
    public static int mobSlot(final int flatIndex) {
        if (flatIndex < 0 || flatIndex >= MAX_MOBS) {
            return -1;
        }
        final int row = flatIndex % MOB_ROWS;
        final int column = flatIndex / MOB_ROWS;
        return FIRST_MOB_SLOT + row * 9 + column;
    }

    /** Decodes a mob slot back into its flat mob index, or -1. */
    public static int mobIndexAt(final int slot) {
        final int offset = slot - FIRST_MOB_SLOT;
        if (offset < 0) {
            return -1;
        }
        final int row = offset / 9;
        final int column = offset % 9;
        if (row >= MOB_ROWS || column >= MOB_COLUMNS) {
            return -1;
        }
        return column * MOB_ROWS + row;
    }

    /**
     * The mob at flat index {@code flatIndex} across all groups (group
     * order, then mob order within the group), or null when out of range.
     */
    public static SpawnerMob mob(final SpawnerConfig config, final int flatIndex) {
        if (flatIndex < 0) {
            return null;
        }
        int remaining = flatIndex;
        for (final SpawnerGroup group : config.groups()) {
            final List<SpawnerMob> mobs = group.mobs();
            if (remaining < mobs.size()) {
                return mobs.get(remaining);
            }
            remaining -= mobs.size();
        }
        return null;
    }

    private SpawnerMenuLayout() {
    }
}
