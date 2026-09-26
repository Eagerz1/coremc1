package com.coremc.core.util;

import java.util.Locale;

/**
 * The CoreMC GUI design language, in one place.
 *
 * <p>Rules every CoreMC menu follows:</p>
 * <ul>
 *   <li>lore is small caps ({@link SmallCaps}) and short — a handful
 *       of lines, blank lines separating blocks of information,</li>
 *   <li>standard Minecraft {@code &} colour codes only, never
 *       MiniMessage,</li>
 *   <li>anything with a price or a requirement carries a live
 *       affordability indicator: {@code &a$25,000 &a✔} when the player
 *       can pay, {@code &c$25,000 &c✖} when they cannot,</li>
 *   <li>values are coloured, labels are grey, click actions are
 *       yellow and locked things are dark grey — never the same as a
 *       purchasable thing.</li>
 * </ul>
 *
 * <p>Everything here is pure text maths, so it is unit-tested without
 * a server.</p>
 */
public final class GuiText {

    /** Affordable / requirement-met marker. */
    public static final String TICK = "\u2714";
    /** Unaffordable / requirement-missing marker. */
    public static final String CROSS = "\u2716";

    private GuiText() {
    }

    // ------------------------------------------------------------------
    // text
    // ------------------------------------------------------------------

    /** Small-caps lore text (colour codes preserved). */
    public static String caps(final String text) {
        return SmallCaps.of(text);
    }

    /** A blank lore separator line. */
    public static String blank() {
        return "";
    }

    /** Yellow call to action: {@code &eᴄʟɪᴄᴋ ᴛᴏ ᴘᴜʀᴄʜᴀsᴇ}. */
    public static String click(final String action) {
        return "&e" + caps(action);
    }

    /** Dark-grey hint line (secondary actions, shift-click tips). */
    public static String hint(final String text) {
        return "&8" + caps(text);
    }

    /** Grey label with a coloured value: {@code &7ᴛɪᴇʀ: &fɪɪɪ}. */
    public static String line(final String label, final String valueColor, final String value) {
        return "&7" + caps(label) + ": " + valueColor + caps(value);
    }

    /** Grey label with an already-formatted (never capsed) value, e.g. money. */
    public static String value(final String label, final String valueColor, final String value) {
        return "&7" + caps(label) + ": " + valueColor + value;
    }

    // ------------------------------------------------------------------
    // affordability
    // ------------------------------------------------------------------

    /**
     * A cost line with the live affordability indicator:
     * {@code &7ᴘʀɪᴄᴇ: &a$25,000 &a✔} or
     * {@code &7ᴘʀɪᴄᴇ: &c$25,000 &c✖}.
     *
     * @param label      e.g. {@code "price"} or {@code "upgrade cost"}
     * @param amountText the formatted amount, e.g. {@code $25,000}
     * @param affordable whether the viewer can pay it right now
     */
    public static String cost(final String label, final String amountText, final boolean affordable) {
        final String color = affordable ? "&a" : "&c";
        return "&7" + caps(label) + ": " + color + amountText + " " + color + mark(affordable);
    }

    /**
     * A requirement line with the same indicator, for non-money
     * requirements: {@code &7ʀᴇǫᴜɪʀᴇs: &a2,500 ɪsʟᴀɴᴅ ᴘᴏɪɴᴛs &a✔}.
     */
    public static String requirement(final String label, final String text, final boolean met) {
        final String color = met ? "&a" : "&c";
        return "&7" + caps(label) + ": " + color + caps(text) + " " + color + mark(met);
    }

    /** The bare ✔ / ✖ marker. */
    public static String mark(final boolean ok) {
        return ok ? TICK : CROSS;
    }

    // ------------------------------------------------------------------
    // numbers
    // ------------------------------------------------------------------

    /** Grouped money without noise: {@code $25,000}, {@code $1,234.50}. */
    public static String money(final double amount) {
        return "$" + number(amount);
    }

    /** Grouped number: {@code 25,000}, {@code 1,234.5}, {@code 0.2}. */
    public static String number(final double amount) {
        final double rounded = Math.round(amount * 100.0) / 100.0;
        if (rounded == Math.floor(rounded) && !Double.isInfinite(rounded)) {
            return String.format(Locale.US, "%,d", (long) rounded);
        }
        return trimZeros(String.format(Locale.US, "%,.2f", rounded));
    }

    private static String trimZeros(final String text) {
        String result = text;
        while (result.endsWith("0")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.endsWith(".") ? result.substring(0, result.length() - 1) : result;
    }

    /** Compact duration: {@code 30s}, {@code 2m}, {@code 2m 30s}, {@code 1h 5m}. */
    public static String seconds(final long totalSeconds) {
        final long safe = Math.max(0, totalSeconds);
        if (safe < 60) {
            return safe + "s";
        }
        if (safe < 3600) {
            final long minutes = safe / 60;
            final long rest = safe % 60;
            return rest == 0 ? minutes + "m" : minutes + "m " + rest + "s";
        }
        final long hours = safe / 3600;
        final long minutes = (safe % 3600) / 60;
        return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
    }

    /** Roman numeral for a tier (1–40, falls back to digits outside). */
    public static String roman(final int value) {
        if (value < 1 || value > 40) {
            return String.valueOf(value);
        }
        final int[] numbers = {40, 10, 9, 5, 4, 1};
        final String[] letters = {"XL", "X", "IX", "V", "IV", "I"};
        final StringBuilder out = new StringBuilder();
        int remaining = value;
        for (int index = 0; index < numbers.length; index++) {
            while (remaining >= numbers[index]) {
                out.append(letters[index]);
                remaining -= numbers[index];
            }
        }
        return out.toString();
    }

    /** Progress as {@code 8/20}. */
    public static String progress(final int current, final int max) {
        return current + "/" + max;
    }
}
