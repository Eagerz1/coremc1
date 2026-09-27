package com.coremc.core.progress.adapter;

import java.util.UUID;

/**
 * Island level / island progression (the Island Progression branch).
 *
 * <p>Until that branch lands, CoreMC still has island points, so this
 * branch ships {@link com.coremc.core.progress.adapter.PointsIslandProgressionAdapter}
 * — a documented, replaceable approximation that derives a level from
 * island points. Achievements read levels through this interface only.</p>
 */
public interface IslandProgressionAdapter extends ProgressAdapter {

    /** No island progression at all. */
    IslandProgressionAdapter ABSENT = new IslandProgressionAdapter() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public int islandLevel(final UUID player) {
            return 0;
        }

        @Override
        public boolean approximated() {
            return false;
        }
    };

    /** The player's island level, or 0 when they have no island. */
    int islandLevel(UUID player);

    /**
     * True when the level is derived locally (island points) rather
     * than read from the real Island Progression system.
     */
    boolean approximated();
}
