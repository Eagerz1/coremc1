package com.coremc.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exactly-once: the same real action may only be counted one time. */
class ProgressDeduplicatorTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static ProgressEvent mine(final UUID player, final String dedupe, final long time) {
        return new ProgressEvent(player, ProgressAction.MINE_BLOCK, "stone", 1,
                ProgressSource.WORLD, dedupe, time);
    }

    @Test
    void theSameActionIsCountedOnce() {
        final ProgressDeduplicator dedupe = new ProgressDeduplicator();
        assertTrue(dedupe.accept(mine(PLAYER, "world:1:2:3", 1_000), 1_000));
        assertFalse(dedupe.accept(mine(PLAYER, "world:1:2:3", 1_500), 1_500));
    }

    @Test
    void theSameActionCountsAgainAfterItsWindow() {
        final ProgressDeduplicator dedupe = new ProgressDeduplicator();
        assertTrue(dedupe.accept(mine(PLAYER, "world:1:2:3", 0), 0));
        assertTrue(dedupe.accept(mine(PLAYER, "world:1:2:3",
                ProgressDeduplicator.DEFAULT_WINDOW_MILLIS + 1),
                ProgressDeduplicator.DEFAULT_WINDOW_MILLIS + 1));
    }

    @Test
    void differentPlayersAndDifferentBlocksBothCount() {
        final ProgressDeduplicator dedupe = new ProgressDeduplicator();
        assertTrue(dedupe.accept(mine(PLAYER, "world:1:2:3", 100), 100));
        assertTrue(dedupe.accept(mine(OTHER, "world:1:2:3", 100), 100));
        assertTrue(dedupe.accept(mine(PLAYER, "world:1:2:4", 100), 100));
    }

    @Test
    void eventsWithoutADedupeKeyAlwaysCount() {
        final ProgressDeduplicator dedupe = new ProgressDeduplicator();
        assertTrue(dedupe.accept(mine(PLAYER, null, 10), 10));
        assertTrue(dedupe.accept(mine(PLAYER, null, 11), 11));
        assertTrue(dedupe.accept(mine(PLAYER, "  ", 12), 12));
        assertEquals(0, dedupe.size());
    }

    @Test
    void rateLimitedActionsUseTheLongWindowAndIgnoreTheDedupeKey() {
        final ProgressDeduplicator dedupe = new ProgressDeduplicator();
        final ProgressEvent first = new ProgressEvent(PLAYER,
                ProgressAction.OMNITOOL_ENCHANT_PROC, "haste", 1, ProgressSource.ROLE, "a", 0);
        final ProgressEvent second = new ProgressEvent(PLAYER,
                ProgressAction.OMNITOOL_ENCHANT_PROC, "haste", 1, ProgressSource.ROLE, "b", 5_000);
        final ProgressEvent later = new ProgressEvent(PLAYER,
                ProgressAction.OMNITOOL_ENCHANT_PROC, "haste", 1, ProgressSource.ROLE, "c",
                ProgressDeduplicator.RATE_LIMIT_WINDOW_MILLIS + 1);
        assertTrue(dedupe.accept(first, first.timestamp()));
        assertFalse(dedupe.accept(second, second.timestamp()));
        assertTrue(dedupe.accept(later, later.timestamp()));
    }

    @Test
    void oldEntriesArePurgedSoTheMapNeverGrowsForever() {
        final ProgressDeduplicator dedupe = new ProgressDeduplicator(1_000, 1_000);
        for (int index = 0; index < 500; index++) {
            dedupe.accept(mine(PLAYER, "block:" + index, index), index);
        }
        assertTrue(dedupe.size() > 0);
        dedupe.accept(mine(PLAYER, "much-later", 1_000_000), 1_000_000);
        assertEquals(1, dedupe.size());
    }

    @Test
    void clearForgetsEverything() {
        final ProgressDeduplicator dedupe = new ProgressDeduplicator();
        dedupe.accept(mine(PLAYER, "a", 1), 1);
        dedupe.clear();
        assertEquals(0, dedupe.size());
        assertTrue(dedupe.accept(mine(PLAYER, "a", 2), 2));
    }
}
