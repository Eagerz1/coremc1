package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Island points: the ledger behind the island top leaderboards.
 * Rounding keeps 0.2-per-action drift away, writes are on demand.
 */
final class IslandPointsServiceTest {

    private static Island island() {
        return new Island(UUID.randomUUID(), UUID.randomUUID(), "Owner",
                "coremc_islands", 1, 0, 64, 0, 100,
                "default", System.currentTimeMillis(), 0, 64, 0, 0f, 0f);
    }

    @Test
    void actionsAccumulateWithoutDrift(@TempDir final Path tempDir) throws IOException {
        final IslandPointsService points = new IslandPointsService(tempDir.resolve("points.yml"));
        points.load();
        final Island island = island();
        for (int i = 0; i < 5; i++) {
            points.add(island, IslandPointsService.ACTION_POINTS);
        }
        assertEquals(1.0, points.points(island), "5 actions x 0.2 = exactly 1");
        for (int i = 0; i < 3; i++) {
            points.add(island, IslandPointsService.ACTION_POINTS);
        }
        assertEquals(1.6, points.points(island));
        points.add(island, IslandPointsService.UPGRADE_POINTS);
        assertEquals(501.6, points.points(island));
        points.add(island, IslandPointsService.CORE_LEVEL_POINTS);
        assertEquals(5501.6, points.points(island));
        assertTrue(points.dirty());
    }

    @Test
    void playingAndUpgradesMatchTheDesign(@TempDir final Path tempDir) throws IOException {
        assertEquals(0.2, IslandPointsService.ACTION_POINTS);
        assertEquals(1.0, IslandPointsService.PLAY_POINTS);
        assertEquals(500.0, IslandPointsService.UPGRADE_POINTS);
        assertEquals(5000.0, IslandPointsService.CORE_LEVEL_POINTS);
    }

    @Test
    void unknownIslandsHoldZero(@TempDir final Path tempDir) throws IOException {
        final IslandPointsService points = new IslandPointsService(tempDir.resolve("points.yml"));
        points.load();
        assertEquals(0.0, points.points(island()));
        assertFalse(points.dirty());
    }

    @Test
    void flushPersistsAndClearsTheDirtyFlag(@TempDir final Path tempDir) throws IOException {
        final IslandPointsService points = new IslandPointsService(tempDir.resolve("points.yml"));
        points.load();
        final Island island = island();
        points.add(island, IslandPointsService.UPGRADE_POINTS);
        assertTrue(points.flush());
        assertFalse(points.dirty());
        assertFalse(points.flush(), "nothing new to write");

        final IslandPointsService reloaded = new IslandPointsService(tempDir.resolve("points.yml"));
        reloaded.load();
        assertEquals(500.0, reloaded.points(island));
    }

    @Test
    void deleteDropsTheIslandsPoints(@TempDir final Path tempDir) throws IOException {
        final IslandPointsService points = new IslandPointsService(tempDir.resolve("points.yml"));
        points.load();
        final Island island = island();
        points.add(island, 10.0);
        points.remove(island);
        assertEquals(0.0, points.points(island));
        points.flush();
        final IslandPointsService reloaded = new IslandPointsService(tempDir.resolve("points.yml"));
        reloaded.load();
        assertEquals(0.0, reloaded.points(island));
    }

    @Test
    void formatShowsCleanNumbers() {
        assertEquals("0.2", IslandPointsService.format(0.2));
        assertEquals("1", IslandPointsService.format(1.0));
        assertEquals("500", IslandPointsService.format(500));
        assertEquals("1,234.5", IslandPointsService.format(1234.5));
        assertEquals("5,000", IslandPointsService.format(5000));
    }
}
