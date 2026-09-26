package com.coremc.core.util;

/**
 * Small-caps text for the CoreMC GUI design language.
 *
 * <p>Lore is written in plain, readable ASCII everywhere in the code
 * and config, and rendered as small caps ({@code ɪʀᴏɴ ɢᴇɴᴇʀᴀᴛᴏʀ}) at
 * the last moment. Only letters that have a readable small-cap glyph
 * are swapped — {@code s} and {@code x} already read as small caps,
 * digits and punctuation are untouched — and standard Minecraft
 * {@code &} colour codes are stepped over so {@code &a} never turns
 * into {@code &ᴀ}. MiniMessage is never used.</p>
 */
public final class SmallCaps {

    /** Small-cap glyph per ASCII letter a–z (index 0 = 'a'). */
    private static final char[] GLYPHS = {
            '\u1D00', // a
            '\u0299', // b
            '\u1D04', // c
            '\u1D05', // d
            '\u1D07', // e
            '\u0493', // f
            '\u0262', // g
            '\u029C', // h
            '\u026A', // i
            '\u1D0A', // j
            '\u1D0B', // k
            '\u029F', // l
            '\u1D0D', // m
            '\u0274', // n
            '\u1D0F', // o
            '\u1D18', // p
            '\u01EB', // q
            '\u0280', // r
            's',      // s — plain s already reads as a small cap
            '\u1D1B', // t
            '\u1D1C', // u
            '\u1D20', // v
            '\u1D21', // w
            'x',      // x — plain x already reads as a small cap
            '\u028F', // y
            '\u1D22'  // z
    };

    private SmallCaps() {
    }

    /**
     * Small-caps version of {@code text}, preserving {@code &} colour
     * and formatting codes (and everything that is not a letter).
     */
    public static String of(final String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            final char current = text.charAt(index);
            if (current == '&' && index + 1 < text.length()) {
                // a colour/format code: copy both chars verbatim
                out.append(current).append(text.charAt(index + 1));
                index++;
                continue;
            }
            out.append(glyph(current));
        }
        return out.toString();
    }

    /** The small-cap glyph of one character (unchanged when there is none). */
    private static char glyph(final char character) {
        if (character >= 'a' && character <= 'z') {
            return GLYPHS[character - 'a'];
        }
        if (character >= 'A' && character <= 'Z') {
            return GLYPHS[character - 'A'];
        }
        return character;
    }
}
