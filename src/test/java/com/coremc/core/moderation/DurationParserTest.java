package com.coremc.core.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DurationParserTest {

    @Test
    void parsesPermanentAndCompoundDurations() {
        assertEquals(0L, DurationParser.parseMillis("permanent").orElseThrow());
        assertEquals(90_000L, DurationParser.parseMillis("1m30s").orElseThrow());
        assertEquals(7L * 24L * 60L * 60L * 1000L, DurationParser.parseMillis("7d").orElseThrow());
    }

    @Test
    void rejectsInvalidDurations() {
        assertTrue(DurationParser.parseMillis("soon").isEmpty());
        assertTrue(DurationParser.parseMillis("0m").isEmpty());
        assertTrue(DurationParser.parseMillis("1x").isEmpty());
    }
}
