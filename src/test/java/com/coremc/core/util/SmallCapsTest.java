package com.coremc.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The small-caps renderer behind every CoreMC lore line: letters turn
 * into small caps, colour codes survive untouched and everything else
 * (digits, punctuation, symbols) is left alone.
 */
class SmallCapsTest {

    @Test
    void lettersBecomeSmallCaps() {
        assertEquals("ɪʀᴏɴ ɢᴇɴᴇʀᴀᴛᴏʀ", SmallCaps.of("Iron Generator"));
        assertEquals("ᴜᴘɢʀᴀᴅᴇ", SmallCaps.of("upgrade"));
    }

    @Test
    void colourCodesAreNeverConverted() {
        assertEquals("&7ᴘʀɪᴄᴇ: &a$25,000", SmallCaps.of("&7price: &a$25,000"));
        // &a must stay &a — the 'a' is a colour code, not a letter
        assertTrue(SmallCaps.of("&aok").startsWith("&a"));
        assertFalse(SmallCaps.of("&aok").contains("&ᴀ"));
    }

    @Test
    void boldAndResetCodesSurvive() {
        assertEquals("&a&lɪʀᴏɴ", SmallCaps.of("&a&lIron"));
        assertEquals("&r", SmallCaps.of("&r"));
    }

    @Test
    void digitsAndPunctuationAreUntouched() {
        assertEquals("30s", SmallCaps.of("30s"));
        assertEquals("8/20", SmallCaps.of("8/20"));
        assertEquals("$1,500 ✔", SmallCaps.of("$1,500 ✔"));
    }

    @Test
    void sAndXStayReadable() {
        // both already read as small caps; swapping them hurts legibility
        assertEquals("sᴋʏ", SmallCaps.of("sky"));
        assertEquals("xᴘ", SmallCaps.of("xp"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals("", SmallCaps.of(null));
        assertEquals("", SmallCaps.of(""));
    }

    @Test
    void trailingAmpersandDoesNotCrash() {
        assertEquals("ᴀ&", SmallCaps.of("a&"));
    }
}
