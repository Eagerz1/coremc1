package com.coremc.core.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventScheduleTest {

    private static final long HOUR = 60L * 60L * 1000L;

    @Test
    void waitsForFirstAnchorThenRepeatsAtConfiguredInterval() {
        final long anchor = 10L * HOUR;
        final long interval = 4L * HOUR;
        final long duration = HOUR;

        assertFalse(EventSchedule.at(anchor - 1, anchor, interval, duration).active());
        assertEquals(anchor, EventSchedule.at(anchor - 1, anchor, interval, duration).nextStartMillis());
        assertTrue(EventSchedule.at(anchor, anchor, interval, duration).active());
        assertTrue(EventSchedule.at(anchor + duration - 1, anchor, interval, duration).active());
        assertFalse(EventSchedule.at(anchor + duration, anchor, interval, duration).active());
        assertEquals(anchor + interval,
                EventSchedule.at(anchor + duration, anchor, interval, duration).nextStartMillis());
        assertTrue(EventSchedule.at(anchor + interval, anchor, interval, duration).active());
    }

    @Test
    void exposesFourEventProgressSteps() {
        final long start = 100L;
        final var window = EventSchedule.at(start, start, HOUR, HOUR);
        assertEquals(0, window.completedSegments(start));
        assertEquals(1, window.completedSegments(start + 15L * 60L * 1000L));
        assertEquals(2, window.completedSegments(start + 30L * 60L * 1000L));
        assertEquals(3, window.completedSegments(start + 45L * 60L * 1000L));
        assertEquals(0.25, window.bossBarProgress(start + 45L * 60L * 1000L), 0.0001);
    }

    @Test
    void rejectsInvalidOrOverlappingWindows() {
        assertThrows(IllegalArgumentException.class, () -> EventSchedule.at(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> EventSchedule.at(0, 0, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> EventSchedule.at(0, 0, 100, 101));
    }
}
