package com.coremc.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Wall-clock schedule maths: fixed grid openings, window boundaries,
 * next-open recovery — the piece that makes restarts land exactly
 * where the clock says instead of resetting the schedule.
 */
class MarketScheduleTest {

    private static final long HOUR = 3_600_000;
    private static final long MINUTE = 60_000;

    /** 6h grid, 30m window, 15m anchor offset (the shipped defaults). */
    private final MarketSchedule schedule = new MarketSchedule(6 * HOUR, 30 * MINUTE, 15 * MINUTE);

    @Test
    void invalidSchedulesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new MarketSchedule(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new MarketSchedule(HOUR, HOUR, 0));
        assertThrows(IllegalArgumentException.class, () -> new MarketSchedule(HOUR, 2 * HOUR, 0));
    }

    @Test
    void openingsSitOnTheOffsetGrid() {
        // grid slots: 0:15, 6:15, 12:15, ... (offset 15m)
        assertEquals(15 * MINUTE, schedule.currentSlotStart(15 * MINUTE));
        assertEquals(15 * MINUTE, schedule.currentSlotStart(3 * HOUR));
        assertEquals(6 * HOUR + 15 * MINUTE, schedule.currentSlotStart(7 * HOUR));
        // before the first offset slot of the day we belong to the previous slot
        assertEquals(-6 * HOUR + 15 * MINUTE, schedule.currentSlotStart(10 * MINUTE));
    }

    @Test
    void windowBoundariesAreExact() {
        final long slot = 6 * HOUR + 15 * MINUTE;
        assertTrue(schedule.isOpenWindow(slot));
        assertTrue(schedule.isOpenWindow(slot + 30 * MINUTE - 1));
        assertFalse(schedule.isOpenWindow(slot + 30 * MINUTE));
        assertEquals(slot + 30 * MINUTE, schedule.closeAt(slot + 10 * MINUTE));
    }

    @Test
    void dueOpenAtRecoversFromAnyRestartPoint() {
        final long slot = 6 * HOUR + 15 * MINUTE;
        // restart inside the open window: the CURRENT slot is due
        assertEquals(slot, schedule.dueOpenAt(slot + 5 * MINUTE));
        // restart after the window closed: the NEXT slot is due
        assertEquals(slot + 6 * HOUR, schedule.dueOpenAt(slot + 31 * MINUTE));
        // restart hours later, several missed cycles: still the next grid slot
        assertEquals(slot + 6 * HOUR, schedule.dueOpenAt(slot + 5 * HOUR));
    }

    @Test
    void rotationIdsAreStablePerWindow() {
        final long slot = 12 * HOUR + 15 * MINUTE;
        // the same window always derives the same id — that is how a
        // restart keeps its rotation instead of duplicating it
        assertEquals(schedule.rotationId(schedule.currentSlotStart(slot + MINUTE)),
                schedule.rotationId(schedule.currentSlotStart(slot + 20 * MINUTE)));
        assertFalse(schedule.rotationId(slot).equals(schedule.rotationId(slot + 6 * HOUR)));
    }
}
