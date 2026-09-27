package com.coremc.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Legacy colour / gradient rendering rules (no MiniMessage anywhere). */
class ChatRenderTest {

    private static final char S = ChatRender.SECTION;

    @Test
    void solidColourUsesLegacyCode() {
        assertEquals(S + "chello", ChatRender.solid("&c", "hello", false));
        assertEquals(S + "c" + S + "lhello", ChatRender.solid("&c", "hello", true));
        assertEquals(S + "chello", ChatRender.solid("c", "hello", false));
    }

    @Test
    void solidHexUsesUnusualXFormat() {
        assertEquals(S + "x" + S + "f" + S + "f" + S + "5" + S + "5" + S + "5" + S + "5" + "hi",
                ChatRender.solid("#ff5555", "hi", false));
    }

    @Test
    void invalidColourLeavesTextUnpainted() {
        assertEquals("hi", ChatRender.solid("not-a-colour", "hi", false));
        assertEquals("", ChatRender.solid("&c", "", false));
    }

    @Test
    void hexParsingIsStrict() {
        assertEquals("ff5555", ChatRender.normaliseHex("#FF5555"));
        assertEquals("00aa00", ChatRender.normaliseHex("00AA00"));
        assertNull(ChatRender.normaliseHex("#ff55"));
        assertNull(ChatRender.normaliseHex("#gg5555"));
        assertNull(ChatRender.normaliseHex(null));
        assertEquals("", ChatRender.hexToSection("nope"));
    }

    @Test
    void gradientPaintsEveryCharacterAndKeepsText() {
        final String rendered = ChatRender.gradient("#ff5555", "#ffaa00", "abcd", false, 64);
        assertEquals("abcd", strip(rendered));
        assertTrue(rendered.startsWith(ChatRender.hexToSection("#ff5555")), "starts at the first stop");
        assertTrue(rendered.contains(ChatRender.hexToSection("#ffaa00")), "reaches the last stop");
        assertEquals(4, countHexStops(rendered));
    }

    @Test
    void gradientPreservesUnicodePunctuationAndEmoji() {
        final String text = "héllo — “quoted”, 你好! 😀";
        final String rendered = ChatRender.gradient("#55ffff", "#5555ff", text, false, 64);
        assertEquals(text, strip(rendered), "no code point may be lost or reordered");
    }

    @Test
    void gradientSegmentCapBoundsColourChanges() {
        final String text = "x".repeat(400);
        final String rendered = ChatRender.gradient("#aa0000", "#ff5555", text, false, 8);
        assertEquals(text, strip(rendered));
        assertEquals(8, countHexStops(rendered), "colour stops are capped");
    }

    @Test
    void gradientBoldAddsBoldAfterEveryColour() {
        final String rendered = ChatRender.gradient("#ff5555", "#ffaa00", "ab", true, 64);
        assertEquals("ab", strip(rendered));
        assertEquals(2, countOccurrences(rendered, "" + S + "l"));
    }

    @Test
    void gradientWithBadStopsDegradesToPlainText() {
        assertEquals("hello", ChatRender.gradient("nope", "#ffaa00", "hello", false, 64));
        assertEquals(S + "lhello", ChatRender.gradient("nope", "#ffaa00", "hello", true, 64));
    }

    @Test
    void singleCharacterGradientUsesTheFirstStop() {
        assertEquals(ChatRender.hexToSection("#ff5555") + "a",
                ChatRender.gradient("#ff5555", "#ffaa00", "a", false, 64));
    }

    @Test
    void sanitiseStripsSectionCodesAlways() {
        assertEquals("hello", ChatRender.sanitise(S + "chel" + S + "llo", true));
        assertEquals("hello", ChatRender.sanitise(S + "chel" + S + "llo", false));
    }

    @Test
    void sanitiseStripsAmpersandCodesWithoutPermission() {
        assertEquals("hello", ChatRender.sanitise("&chel&llo", false));
        assertEquals("&chel&llo", ChatRender.sanitise("&chel&llo", true));
        // a lone ampersand is ordinary punctuation, never a code
        assertEquals("rock & roll", ChatRender.sanitise("rock & roll", false));
        assertEquals("100% & &&", ChatRender.sanitise("100% & &&", false));
    }

    @Test
    void sanitisePreservesUnicodeAndPunctuation() {
        final String text = "¡Hola! ✓ 你好 — 😀 «quote» 3.14?";
        assertEquals(text, ChatRender.sanitise(text, false));
    }

    @Test
    void clampLimitsLengthWithoutSplittingSurrogates() {
        assertEquals("abc", ChatRender.clamp("abcdef", 3));
        assertEquals("abc", ChatRender.clamp("abc", 10));
        final String emoji = "😀😀😀";
        final String clamped = ChatRender.clamp(emoji, 2);
        assertTrue(clamped.length() <= 4);
        assertFalse(Character.isHighSurrogate(clamped.charAt(clamped.length() - 1)),
                "never ends on a dangling surrogate");
    }

    @Test
    void renderedStyleNeverContainsMiniMessageMarkup() {
        final String rendered = ChatRender.gradient("#ff5555", "#ffaa00", "hello", true, 64);
        assertFalse(rendered.contains("<"), "no MiniMessage tags");
        assertFalse(rendered.contains(">"), "no MiniMessage tags");
    }

    private static String strip(final String input) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == S) {
                i++; // skip the selector
                continue;
            }
            out.append(input.charAt(i));
        }
        return out.toString();
    }

    private static int countHexStops(final String input) {
        return countOccurrences(input, "" + S + "x");
    }

    private static int countOccurrences(final String haystack, final String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
