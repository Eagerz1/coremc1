package com.coremc.core.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/** Immutable offence-number to action mapping for one moderation tier. */
public final class TierSchedule {

    private final int tier;
    private final List<Entry> entries;

    public TierSchedule(final int tier, final List<Entry> entries) {
        this.tier = tier;
        this.entries = List.copyOf(entries);
        if (this.entries.isEmpty()) {
            throw new IllegalArgumentException("tier schedule may not be empty");
        }
    }

    public int tier() {
        return tier;
    }

    public TierAction actionFor(final int offense) {
        final int safe = Math.max(1, offense);
        Entry last = entries.get(0);
        for (final Entry entry : entries) {
            if (entry.matches(safe)) {
                return entry.action();
            }
            if (entry.minOffense <= safe) {
                last = entry;
            }
        }
        return last.action();
    }

    public List<Entry> entries() {
        return entries;
    }

    @SuppressWarnings("unchecked")
    public static TierSchedule parse(final int tier, final List<Map<?, ?>> rawEntries) {
        final List<Entry> parsed = new ArrayList<>();
        for (final Map<?, ?> rawEntry : rawEntries) {
            final Map<String, Object> entry = new java.util.LinkedHashMap<>();
            for (final Map.Entry<?, ?> raw : rawEntry.entrySet()) {
                entry.put(String.valueOf(raw.getKey()), raw.getValue());
            }
            final int min = intValue(entry.get("min"), intValue(entry.get("offense"), 1));
            final int max = maxValue(entry.get("max"), min);
            final ModerationAction action = ModerationAction.parse(String.valueOf(entry.get("action")), ModerationAction.WARN);
            final String durationRaw = String.valueOf(entry.getOrDefault("duration", ""));
            final long durationMillis;
            if (action == ModerationAction.MUTE || action == ModerationAction.BAN) {
                final OptionalLong parsedDuration = DurationParser.parseMillis(durationRaw);
                durationMillis = parsedDuration.orElse(0L);
            } else {
                durationMillis = 0L;
            }
            parsed.add(new Entry(Math.max(1, min), max < min ? min : max,
                    new TierAction(action, durationMillis)));
        }
        if (parsed.isEmpty()) {
            return defaultFor(tier);
        }
        parsed.sort(java.util.Comparator.comparingInt(Entry::minOffense));
        return new TierSchedule(tier, parsed);
    }

    public static TierSchedule defaultFor(final int tier) {
        return switch (tier) {
            case 1 -> new TierSchedule(1, List.of(
                    exact(1, ModerationAction.WARN, 0L),
                    exact(2, ModerationAction.WARN, 0L),
                    exact(3, ModerationAction.MUTE, minutes(5)),
                    exact(4, ModerationAction.MUTE, minutes(15)),
                    exact(5, ModerationAction.MUTE, minutes(30)),
                    exact(6, ModerationAction.MUTE, hours(1)),
                    exact(7, ModerationAction.MUTE, hours(6)),
                    exact(8, ModerationAction.MUTE, hours(12)),
                    range(9, 24, ModerationAction.MUTE, days(1)),
                    range(25, 29, ModerationAction.MUTE, days(3)),
                    range(30, 39, ModerationAction.MUTE, days(7)),
                    range(40, 49, ModerationAction.MUTE, days(30)),
                    range(50, Integer.MAX_VALUE, ModerationAction.MUTE, 0L)));
            case 2 -> new TierSchedule(2, List.of(
                    exact(1, ModerationAction.WARN, 0L),
                    exact(2, ModerationAction.BAN, days(1)),
                    exact(3, ModerationAction.BAN, days(3)),
                    exact(4, ModerationAction.BAN, days(7)),
                    exact(5, ModerationAction.BAN, days(14)),
                    range(6, Integer.MAX_VALUE, ModerationAction.BAN, days(30))));
            case 3 -> new TierSchedule(3, List.of(
                    exact(1, ModerationAction.BAN, days(1)),
                    exact(2, ModerationAction.BAN, days(7)),
                    exact(3, ModerationAction.BAN, days(30)),
                    range(4, Integer.MAX_VALUE, ModerationAction.BAN, 0L)));
            case 4 -> new TierSchedule(4, List.of(
                    exact(1, ModerationAction.BAN, days(7)),
                    exact(2, ModerationAction.BAN, days(30)),
                    range(3, Integer.MAX_VALUE, ModerationAction.BAN, 0L)));
            case 5 -> new TierSchedule(5, List.of(
                    range(1, Integer.MAX_VALUE, ModerationAction.BAN, 0L)));
            default -> throw new IllegalArgumentException("Unknown tier " + tier);
        };
    }

    public static Entry exact(final int offense, final ModerationAction action, final long durationMillis) {
        return range(offense, offense, action, durationMillis);
    }

    public static Entry range(final int min, final int max, final ModerationAction action, final long durationMillis) {
        return new Entry(min, max, new TierAction(action, durationMillis));
    }

    private static long minutes(final long minutes) {
        return minutes * 60_000L;
    }

    private static long hours(final long hours) {
        return minutes(hours * 60L);
    }

    private static long days(final long days) {
        return hours(days * 24L);
    }

    private static int intValue(final Object value, final int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (final NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int maxValue(final Object value, final int fallback) {
        if (value == null) {
            return fallback;
        }
        final String raw = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (raw.equals("+")) {
            return Integer.MAX_VALUE;
        }
        if (raw.endsWith("+")) {
            return Integer.MAX_VALUE;
        }
        return intValue(value, fallback);
    }

    public record Entry(int minOffense, int maxOffense, TierAction action) {
        public boolean matches(final int offense) {
            return offense >= minOffense && offense <= maxOffense;
        }
    }

    public record TierAction(ModerationAction action, long durationMillis) {
        public boolean permanent() {
            return durationMillis == 0L && (action == ModerationAction.MUTE || action == ModerationAction.BAN);
        }

        public String durationText() {
            return action == ModerationAction.MUTE || action == ModerationAction.BAN
                    ? DurationParser.formatMillis(durationMillis)
                    : "none";
        }
    }
}
