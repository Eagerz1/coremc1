package com.coremc.core.moderation;

import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats moderation durations without touching Bukkit APIs. */
public final class DurationParser {

    private static final Pattern TOKEN = Pattern.compile("(\\d+)(mo|[smhdwy])", Pattern.CASE_INSENSITIVE);
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60L * SECOND;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    private static final long WEEK = 7L * DAY;
    private static final long MONTH = 30L * DAY;
    private static final long YEAR = 365L * DAY;

    private DurationParser() {
    }

    /**
     * Parses a duration such as {@code 5m}, {@code 1h30m}, {@code 7d},
     * {@code 2w}, or {@code permanent}. Empty means invalid, zero means permanent.
     */
    public static OptionalLong parseMillis(final String raw) {
        if (raw == null || raw.isBlank()) {
            return OptionalLong.empty();
        }
        final String input = raw.trim().toLowerCase(Locale.ROOT);
        if (input.equals("perm") || input.equals("permanent") || input.equals("forever") || input.equals("never")) {
            return OptionalLong.of(0L);
        }
        long total = 0L;
        int cursor = 0;
        final Matcher matcher = TOKEN.matcher(input);
        while (matcher.find()) {
            if (matcher.start() != cursor) {
                return OptionalLong.empty();
            }
            final long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (final NumberFormatException exception) {
                return OptionalLong.empty();
            }
            final long unit = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> SECOND;
                case "m" -> MINUTE;
                case "h" -> HOUR;
                case "d" -> DAY;
                case "w" -> WEEK;
                case "mo" -> MONTH;
                case "y" -> YEAR;
                default -> throw new IllegalStateException("unreachable duration unit");
            };
            try {
                total = Math.addExact(total, Math.multiplyExact(amount, unit));
            } catch (final ArithmeticException overflow) {
                return OptionalLong.empty();
            }
            cursor = matcher.end();
        }
        if (cursor != input.length() || total <= 0L) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(total);
    }

    public static String formatMillis(final long millis) {
        if (millis == 0L) {
            return "permanent";
        }
        final Duration duration = Duration.ofMillis(Math.max(0L, millis));
        final long days = duration.toDays();
        if (days >= 365 && days % 365 == 0) {
            return (days / 365) + "y";
        }
        if (days > 0) {
            return days + "d";
        }
        final long hours = duration.toHours();
        if (hours > 0) {
            return hours + "h";
        }
        final long minutes = duration.toMinutes();
        if (minutes > 0) {
            return minutes + "m";
        }
        return Math.max(1L, duration.toSeconds()) + "s";
    }

    public static String formatRemaining(final long expiresAtMillis, final long nowMillis) {
        if (expiresAtMillis == 0L) {
            return "permanent";
        }
        return formatMillis(Math.max(0L, expiresAtMillis - nowMillis));
    }
}
