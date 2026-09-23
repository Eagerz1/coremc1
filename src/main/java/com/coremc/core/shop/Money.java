package com.coremc.core.shop;

import java.util.Locale;

/**
 * Coin math: every balance is rounded to two decimal places after each
 * transaction so floating-point drift can never accumulate, and every
 * user-visible amount is rendered through {@link #format}.
 */
public final class Money {

    private Money() {
    }

    /** Rounds to cents. */
    public static double round(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Renders as {@code $12.50} (symbol configurable, always two decimals). */
    public static String format(final double value, final String symbol) {
        return symbol + String.format(Locale.US, "%.2f", round(value));
    }
}
