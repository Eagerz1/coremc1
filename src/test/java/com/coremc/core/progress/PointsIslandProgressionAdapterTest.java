package com.coremc.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.progress.adapter.ExternalSystems;
import com.coremc.core.progress.adapter.PointsIslandProgressionAdapter;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The island level stand-in, and the adapter registry that lets the
 * real systems replace every stand-in with one call.
 */
class PointsIslandProgressionAdapterTest {

    private static final double STEP = PointsIslandProgressionAdapter.DEFAULT_STEP;

    @Test
    void levelsFollowTheDocumentedTriangularCurve() {
        assertEquals(0, PointsIslandProgressionAdapter.levelFor(0, STEP));
        assertEquals(0, PointsIslandProgressionAdapter.levelFor(249, STEP));
        assertEquals(1, PointsIslandProgressionAdapter.levelFor(250, STEP));
        assertEquals(2, PointsIslandProgressionAdapter.levelFor(750, STEP));
        assertEquals(5, PointsIslandProgressionAdapter.levelFor(3_750, STEP));
        assertEquals(15, PointsIslandProgressionAdapter.levelFor(30_000, STEP));
        assertEquals(30, PointsIslandProgressionAdapter.levelFor(116_250, STEP));
    }

    @Test
    void theCurveIsMonotonicAndMatchesItsInverse() {
        int previous = 0;
        for (int points = 0; points < 20_000; points += 137) {
            final int level = PointsIslandProgressionAdapter.levelFor(points, STEP);
            assertTrue(level >= previous, "levels must never go down");
            previous = level;
        }
        for (int level = 1; level <= 40; level++) {
            final double needed = PointsIslandProgressionAdapter.pointsForLevel(level, STEP);
            assertEquals(level, PointsIslandProgressionAdapter.levelFor(needed, STEP));
            assertEquals(level - 1, PointsIslandProgressionAdapter.levelFor(needed - 1, STEP));
        }
    }

    @Test
    void nonsenseInputsAreSafe() {
        assertEquals(0, PointsIslandProgressionAdapter.levelFor(-100, STEP));
        assertEquals(0, PointsIslandProgressionAdapter.levelFor(1_000, 0));
        assertEquals(0, PointsIslandProgressionAdapter.pointsForLevel(0, STEP));
        final PointsIslandProgressionAdapter adapter =
                new PointsIslandProgressionAdapter(null, null);
        assertFalse(adapter.available());
        assertTrue(adapter.approximated());
        assertEquals(0, adapter.islandLevel(UUID.randomUUID()));
        assertEquals(0, adapter.islandLevel(null));
    }

    @Test
    void absentSystemsReportThemselvesInsteadOfLying() {
        final ExternalSystems externals = new ExternalSystems();
        assertFalse(externals.islands().available());
        assertFalse(externals.roles().available());
        assertFalse(externals.omniTools().available());
        assertFalse(externals.companions().available());
        assertFalse(externals.seasonJourney().available());
        assertFalse(externals.questsAndEvents().available());
        assertFalse(externals.miningCube().available());
        assertFalse(externals.rewards().available());
        assertFalse(externals.missingSummary().isBlank());
    }

    @Test
    void registeringARealAdapterReplacesTheStandIn() {
        final ExternalSystems externals = new ExternalSystems();
        externals.islands(new com.coremc.core.progress.adapter.IslandProgressionAdapter() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public int islandLevel(final UUID player) {
                return 42;
            }

            @Override
            public boolean approximated() {
                return false;
            }
        });
        assertTrue(externals.islands().available());
        assertEquals(42, externals.islands().islandLevel(UUID.randomUUID()));
        externals.islands(null);
        assertFalse(externals.islands().available());
    }

    @Test
    void guideLinksAreReadyForTheServerGuide() {
        assertEquals(3, GuideLink.entries().size());
        for (final GuideLink link : GuideLink.entries()) {
            assertTrue(link.command().startsWith("/"), link.id() + " needs a command");
            assertTrue(link.permission().startsWith("coremc."), link.id() + " needs a permission");
            assertFalse(link.description().isBlank(), link.id() + " needs a description");
            assertEquals("progression", link.category());
        }
    }
}
