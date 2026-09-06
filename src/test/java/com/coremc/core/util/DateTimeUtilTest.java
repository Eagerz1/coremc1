package com.coremc.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateTimeUtilTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    void formatsDaysAndHours() {
        final long past = NOW - ((3L * 86_400L + 4L * 3_600L) * 1000L);
        assertEquals("3d 4h", DateTimeUtil.formatAge(past, NOW));
    }

    @Test
    void formatsHoursAndMinutes() {
        final long past = NOW - ((5L * 3_600L + 12L * 60L) * 1000L);
        assertEquals("5h 12m", DateTimeUtil.formatAge(past, NOW));
    }

    @Test
    void formatsMinutesOnly() {
        final long past = NOW - (12L * 60L * 1000L);
        assertEquals("12m", DateTimeUtil.formatAge(past, NOW));
    }

    @Test
    void formatsJustNow() {
        assertEquals("just now", DateTimeUtil.formatAge(NOW - 30_000L, NOW));
    }

    @Test
    void futureNeverNegative() {
        assertEquals("just now", DateTimeUtil.formatAge(NOW + 1_000_000L, NOW));
    }

    @Test
    void timestampHasDateShape() {
        final String formatted = DateTimeUtil.formatTimestamp(NOW);
        assertTrue(formatted.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} .*"), formatted);
    }
}
