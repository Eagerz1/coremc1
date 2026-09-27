package com.coremc.core.progress.adapter;

import com.coremc.core.island.Island;
import com.coremc.core.island.IslandPointsService;
import com.coremc.core.island.IslandService;
import java.util.UUID;

/**
 * The stand-in for the Island Progression branch's island level.
 *
 * <p>CoreMC already tracks island points, so rather than block the
 * progression achievements on another branch this adapter derives a
 * level from them with a documented triangular curve: level {@code L}
 * needs {@code step * L * (L + 1) / 2} points (with the default step
 * of 250: level 1 at 250, level 5 at 3,750, level 15 at 30,000, level
 * 30 at 116,250).</p>
 *
 * <p>It reports {@link #approximated()} true, and the GUI says so. The
 * moment the real system lands, {@code externals.islands(realAdapter)}
 * replaces it and every achievement keeps working — the level is only
 * ever read through this interface.</p>
 */
public final class PointsIslandProgressionAdapter implements IslandProgressionAdapter {

    /** Points needed for level 1; each level costs {@code step} more than the last. */
    public static final double DEFAULT_STEP = 250.0;

    private final IslandService islands;
    private final IslandPointsService points;
    private final double step;

    public PointsIslandProgressionAdapter(final IslandService islands,
                                          final IslandPointsService points) {
        this(islands, points, DEFAULT_STEP);
    }

    public PointsIslandProgressionAdapter(final IslandService islands,
                                          final IslandPointsService points, final double step) {
        this.islands = islands;
        this.points = points;
        this.step = step <= 0 ? DEFAULT_STEP : step;
    }

    @Override
    public boolean available() {
        return islands != null && points != null;
    }

    @Override
    public boolean approximated() {
        return true;
    }

    @Override
    public int islandLevel(final UUID player) {
        if (!available() || player == null) {
            return 0;
        }
        final Island island = islands.islandOf(player);
        if (island == null) {
            return 0;
        }
        return levelFor(points.points(island), step);
    }

    /**
     * Pure level maths: the highest level whose cumulative cost the
     * points cover. Deterministic and unit-tested.
     */
    public static int levelFor(final double islandPoints, final double step) {
        if (islandPoints <= 0 || step <= 0) {
            return 0;
        }
        int level = 0;
        double needed = step;
        double remaining = islandPoints;
        while (remaining >= needed && level < 1_000) {
            remaining -= needed;
            level++;
            needed += step;
        }
        return level;
    }

    /** Cumulative points needed to reach {@code level}. */
    public static double pointsForLevel(final int level, final double step) {
        if (level <= 0 || step <= 0) {
            return 0;
        }
        return step * level * (level + 1) / 2.0;
    }
}
