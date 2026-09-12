package com.coremc.core.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Small date/time formatting helpers used by profile output.
 */
public final class DateTimeUtil {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    private DateTimeUtil() {
    }

    /** Formats an epoch-milli timestamp as {@code yyyy-MM-dd HH:mm z}. */
    public static String formatTimestamp(final long epochMillis) {
        return TIMESTAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * Formats the age of {@code pastMillis} relative to {@code nowMillis}
     * in a compact human form such as {@code "3d 4h"}, {@code "12m"}
     * or {@code "just now"}.
     */
    public static String formatAge(final long pastMillis, final long nowMillis) {
        final long delta = Math.max(0L, nowMillis - pastMillis);
        final Duration duration = Duration.ofMillis(delta);

        final long days = duration.toDays();
        final long hours = duration.toHoursPart();
        final long minutes = duration.toMinutesPart();

        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (duration.toHours() > 0) {
            return duration.toHours() + "h " + minutes + "m";
        }
        if (duration.toMinutes() > 0) {
            return duration.toMinutes() + "m";
        }
        return "just now";
    }
}
