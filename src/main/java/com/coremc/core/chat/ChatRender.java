package com.coremc.core.chat;

import java.util.Locale;

/**
 * Pure legacy-colour rendering for CoreMC chat cosmetics.
 *
 * CoreMC uses standard Minecraft formatting only — '&amp;' codes and the
 * legacy hex form {@code §x§R§R§G§G§B§B} that Paper/Adventure understands.
 * MiniMessage is intentionally not used anywhere in this class (or in the
 * plugin), and nothing here touches Bukkit, so every rule below is unit
 * testable and safe to run on the async chat thread.
 *
 * Responsibilities:
 *  - {@link #sanitise(String, boolean)} strips formatting injected by
 *    players (both '§' and, unless explicitly allowed, '&amp;' codes),
 *  - {@link #solid(String, String, boolean)} paints a whole message in one
 *    colour,
 *  - {@link #gradient(String, String, String, boolean, int)} paints a
 *    smooth two-stop gradient across the message using per-segment hex
 *    colours, with a hard segment cap so a long message can never explode
 *    into thousands of colour changes.
 *
 * Unicode safety: gradients iterate CODE POINTS, never chars, so surrogate
 * pairs (emoji) and combining punctuation survive intact.
 */
public final class ChatRender {

    /** The legacy section character Minecraft uses on the wire. */
    public static final char SECTION = '\u00a7';

    /** Default cap on the number of distinct gradient colour stops. */
    public static final int DEFAULT_GRADIENT_SEGMENTS = 64;

    /** Hard upper bound on any rendered message (safety valve). */
    public static final int DEFAULT_MAX_MESSAGE_LENGTH = 256;

    private ChatRender() {
    }

    /**
     * Removes formatting a player could have typed.
     *
     * '§' sequences are ALWAYS removed (a client can never legitimately
     * produce them). '&amp;' sequences are removed unless {@code allowAmpersand}
     * is true, which callers only pass when the sender holds the configured
     * chat-format permission.
     */
    public static String sanitise(final String input, final boolean allowAmpersand) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            final char current = input.charAt(i);
            final boolean isCodeChar = current == SECTION || (!allowAmpersand && current == '&');
            if (isCodeChar && i + 1 < input.length() && isFormatCode(input.charAt(i + 1))) {
                i++; // drop the code char and its selector
                continue;
            }
            if (current == SECTION) {
                continue; // stray section char — never allowed through
            }
            out.append(current);
        }
        return out.toString();
    }

    /** Whether {@code code} is a legacy colour/format selector (0-9a-fk-or, x). */
    public static boolean isFormatCode(final char code) {
        final char lower = Character.toLowerCase(code);
        return (lower >= '0' && lower <= '9')
                || (lower >= 'a' && lower <= 'f')
                || (lower >= 'k' && lower <= 'o')
                || lower == 'r'
                || lower == 'x';
    }

    /** Truncates to {@code maxLength} code points (never splits a surrogate pair). */
    public static String clamp(final String input, final int maxLength) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        final int limit = maxLength <= 0 ? DEFAULT_MAX_MESSAGE_LENGTH : maxLength;
        if (input.length() <= limit) {
            return input;
        }
        final int end = input.offsetByCodePoints(0, Math.min(limit, input.codePointCount(0, input.length())));
        return input.substring(0, end);
    }

    /**
     * Converts {@code #rrggbb} (or {@code rrggbb}) into the legacy hex form
     * {@code §x§r§r§g§g§b§b}. Returns an empty string for malformed input so
     * a typo in config can never corrupt a chat line.
     */
    public static String hexToSection(final String hex) {
        final String normalised = normaliseHex(hex);
        if (normalised == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(14);
        out.append(SECTION).append('x');
        for (int i = 0; i < 6; i++) {
            out.append(SECTION).append(Character.toLowerCase(normalised.charAt(i)));
        }
        return out.toString();
    }

    /** Validates/normalises a hex colour to 6 lower-case digits, or null. */
    public static String normaliseHex(final String hex) {
        if (hex == null) {
            return null;
        }
        String value = hex.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6) {
            return null;
        }
        for (int i = 0; i < 6; i++) {
            final char c = Character.toLowerCase(value.charAt(i));
            final boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hexDigit) {
                return null;
            }
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * Paints {@code text} in one solid colour.
     *
     * @param colour either a legacy code ({@code &c} / {@code c}) or a hex
     *               value ({@code #ff5555}); blank leaves the text uncoloured
     * @param bold   appends the bold code after the colour
     */
    public static String solid(final String colour, final String text, final boolean bold) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final String prefix = colourPrefix(colour);
        return prefix + (bold ? SECTION + "l" : "") + text;
    }

    /** Legacy prefix for a colour spec ('&amp;c', 'c' or '#rrggbb'); "" when blank/invalid. */
    public static String colourPrefix(final String colour) {
        if (colour == null || colour.isBlank()) {
            return "";
        }
        final String value = colour.trim();
        if (value.startsWith("#") || normaliseHex(value) != null) {
            return hexToSection(value);
        }
        final String code = value.startsWith("&") || value.charAt(0) == SECTION ? value.substring(1) : value;
        if (code.length() == 1 && isFormatCode(code.charAt(0))) {
            return String.valueOf(SECTION) + Character.toLowerCase(code.charAt(0));
        }
        return "";
    }

    /**
     * Paints {@code text} with a two-stop gradient from {@code fromHex} to
     * {@code toHex}.
     *
     * The text is split into at most {@code maxSegments} runs of code points;
     * each run gets one interpolated colour. That keeps the resulting
     * component tree bounded no matter how long the message is, while short
     * messages still get a per-character gradient.
     *
     * Invalid hex stops degrade gracefully to plain (uncoloured) text rather
     * than throwing on the chat thread.
     */
    public static String gradient(
            final String fromHex,
            final String toHex,
            final String text,
            final boolean bold,
            final int maxSegments) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final String from = normaliseHex(fromHex);
        final String to = normaliseHex(toHex);
        if (from == null || to == null) {
            return bold ? SECTION + "l" + text : text;
        }
        final int[] codePoints = text.codePoints().toArray();
        final int length = codePoints.length;
        final int cap = maxSegments <= 0 ? DEFAULT_GRADIENT_SEGMENTS : maxSegments;
        final int segments = Math.min(length, cap);

        final int fromR = Integer.parseInt(from.substring(0, 2), 16);
        final int fromG = Integer.parseInt(from.substring(2, 4), 16);
        final int fromB = Integer.parseInt(from.substring(4, 6), 16);
        final int toR = Integer.parseInt(to.substring(0, 2), 16);
        final int toG = Integer.parseInt(to.substring(2, 4), 16);
        final int toB = Integer.parseInt(to.substring(4, 6), 16);

        final StringBuilder out = new StringBuilder(text.length() * 8);
        int index = 0;
        for (int segment = 0; segment < segments; segment++) {
            final double ratio = segments == 1 ? 0.0D : (double) segment / (double) (segments - 1);
            final int red = (int) Math.round(fromR + (toR - fromR) * ratio);
            final int green = (int) Math.round(fromG + (toG - fromG) * ratio);
            final int blue = (int) Math.round(fromB + (toB - fromB) * ratio);
            out.append(hexToSection(String.format(Locale.ROOT, "%02x%02x%02x", red, green, blue)));
            if (bold) {
                out.append(SECTION).append('l');
            }
            // Distribute the code points evenly across the segments so no
            // character is ever dropped or duplicated.
            final int end = (int) Math.round((double) length * (segment + 1) / (double) segments);
            while (index < end && index < length) {
                out.appendCodePoint(codePoints[index]);
                index++;
            }
        }
        while (index < length) { // rounding guard: never lose a character
            out.appendCodePoint(codePoints[index]);
            index++;
        }
        return out.toString();
    }
}
