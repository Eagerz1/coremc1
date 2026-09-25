package com.coremc.core.essence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PlacedBlockTracker: the anti place-mine loop for Mining Essence —
 * only placements of mining-eligible blocks are remembered, breaking a
 * remembered block removes it again, and the set survives restarts.
 */
class PlacedBlockTrackerTest {

    @Test
    void tracksRemembersAndForgetsPositions(@TempDir final Path dir) {
        final PlacedBlockTracker tracker =
                new PlacedBlockTracker(dir.resolve("placed-blocks.yml"), Logger.getLogger("test"));
        tracker.load();
        assertFalse(tracker.isPlayerPlaced("world", 1, 2, 3));
        tracker.add("world", 1, 2, 3);
        assertTrue(tracker.isPlayerPlaced("world", 1, 2, 3));
        // positions are world-scoped
        assertFalse(tracker.isPlayerPlaced("other", 1, 2, 3));
        tracker.remove("world", 1, 2, 3);
        assertFalse(tracker.isPlayerPlaced("world", 1, 2, 3));
    }

    @Test
    void persistsAcrossRestart(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("placed-blocks.yml");
        final PlacedBlockTracker first = new PlacedBlockTracker(file, Logger.getLogger("test"));
        first.load();
        first.add("coremc_islands", 10, 64, -20);
        first.add("coremc_islands", 11, 64, -20);
        first.remove("coremc_islands", 11, 64, -20);

        final PlacedBlockTracker second = new PlacedBlockTracker(file, Logger.getLogger("test"));
        second.load();
        assertTrue(second.isPlayerPlaced("coremc_islands", 10, 64, -20));
        assertFalse(second.isPlayerPlaced("coremc_islands", 11, 64, -20));
    }

    @Test
    void corruptFileStartsFresh(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("placed-blocks.yml");
        Files.writeString(file, "{unbalanced", StandardCharsets.UTF_8);
        final PlacedBlockTracker tracker = new PlacedBlockTracker(file, Logger.getLogger("test"));
        tracker.load();
        assertFalse(tracker.isPlayerPlaced("world", 0, 0, 0));
        tracker.add("world", 0, 0, 0);
        assertTrue(tracker.isPlayerPlaced("world", 0, 0, 0));
    }

    @Test
    void missingFileStartsEmpty(@TempDir final Path dir) {
        final PlacedBlockTracker tracker =
                new PlacedBlockTracker(dir.resolve("placed-blocks.yml"), Logger.getLogger("test"));
        tracker.load();
        assertFalse(tracker.isPlayerPlaced("world", 1, 1, 1));
        assertEquals(0, Files.exists(dir.resolve("placed-blocks.yml")) ? 1 : 0);
    }
}
