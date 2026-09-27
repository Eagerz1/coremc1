package com.coremc.core.achievements;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One player's permanent Achievement state: progress counters, what
 * has been earned (and in which season), claimed rewards and
 * permanent unlocks.
 *
 * <p>Seasonal Achievements are recorded with the season they were
 * earned in, so "Season 3 Champion" stays visibly a Season 3 badge
 * forever. Nothing here is ever wiped by a season reset.</p>
 */
public final class AchievementProfile {

    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final Map<String, Set<String>> uniqueKeys = new LinkedHashMap<>();
    private final Map<String, Long> earnedAt = new LinkedHashMap<>();
    private final Map<String, Integer> earnedSeason = new LinkedHashMap<>();
    private final Set<String> claimed = new LinkedHashSet<>();
    private final Set<String> unlocks = new LinkedHashSet<>();

    /** Current progress counter for an achievement. */
    public long counter(final String id) {
        return counters.getOrDefault(key(id), 0L);
    }

    /** Adds to a counter (TOTAL mode) and returns the new value. */
    public long add(final String id, final long delta) {
        if (delta <= 0) {
            return counter(id);
        }
        final long value = counter(id) + delta;
        counters.put(key(id), value);
        return value;
    }

    /** Raises a counter to a new high-water mark (MAX mode). */
    public long raise(final String id, final long value) {
        final long current = counter(id);
        if (value > current) {
            counters.put(key(id), value);
            return value;
        }
        return current;
    }

    /** Records a distinct key (UNIQUE mode) and returns how many are known. */
    public long addUnique(final String id, final String subject) {
        final Set<String> seen = uniqueKeys.computeIfAbsent(key(id),
                ignored -> new LinkedHashSet<>());
        seen.add(key(subject));
        counters.put(key(id), (long) seen.size());
        return seen.size();
    }

    /** Sets a counter exactly (storage loading, admin tools). */
    public void set(final String id, final long value) {
        if (value <= 0) {
            counters.remove(key(id));
        } else {
            counters.put(key(id), value);
        }
    }

    /** Every counter (read-only). */
    public Map<String, Long> counters() {
        return Map.copyOf(counters);
    }

    /** Distinct keys seen for an achievement (read-only). */
    public Set<String> uniqueKeys(final String id) {
        return Set.copyOf(uniqueKeys.getOrDefault(key(id), Set.of()));
    }

    /** Restores stored unique keys. */
    public void putUnique(final String id, final Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            uniqueKeys.put(key(id), new LinkedHashSet<>(keys));
        }
    }

    /** Every unique-key set (read-only). */
    public Map<String, Set<String>> uniqueKeys() {
        return Map.copyOf(uniqueKeys);
    }

    // ------------------------------------------------------------------
    // earning
    // ------------------------------------------------------------------

    /** True when the achievement has been earned. */
    public boolean earned(final String id) {
        return earnedAt.containsKey(key(id));
    }

    /**
     * Marks an achievement earned; false when it already was.
     *
     * @param season the season it was earned in, or -1 for non-seasonal
     */
    public boolean earn(final String id, final long when, final int season) {
        final String key = key(id);
        if (earnedAt.containsKey(key)) {
            return false;
        }
        earnedAt.put(key, when);
        if (season >= 0) {
            earnedSeason.put(key, season);
        }
        return true;
    }

    /** When it was earned (epoch millis), or 0. */
    public long earnedAt(final String id) {
        return earnedAt.getOrDefault(key(id), 0L);
    }

    /** The season it was earned in, or -1 when it is not seasonal. */
    public int earnedSeason(final String id) {
        return earnedSeason.getOrDefault(key(id), -1);
    }

    /** Every earned achievement id (read-only). */
    public Set<String> earned() {
        return Set.copyOf(earnedAt.keySet());
    }

    /** Every earned timestamp (read-only). */
    public Map<String, Long> earnedTimes() {
        return Map.copyOf(earnedAt);
    }

    /** Every recorded season stamp (read-only). */
    public Map<String, Integer> earnedSeasons() {
        return Map.copyOf(earnedSeason);
    }

    // ------------------------------------------------------------------
    // claims + unlocks
    // ------------------------------------------------------------------

    /** True when the hand-claimed rewards were already taken. */
    public boolean claimed(final String id) {
        return claimed.contains(key(id));
    }

    /** Marks the rewards claimed; false when they already were. */
    public boolean claim(final String id) {
        return claimed.add(key(id));
    }

    /** Every claim (read-only). */
    public Set<String> claims() {
        return Set.copyOf(claimed);
    }

    /** True when a permanent unlock has been applied. */
    public boolean unlocked(final String unlockKey) {
        return unlocks.contains(key(unlockKey));
    }

    /** Applies a permanent unlock; false when it was already applied. */
    public boolean unlock(final String unlockKey) {
        return unlocks.add(key(unlockKey));
    }

    /** Every unlock (read-only). */
    public Set<String> unlocks() {
        return Set.copyOf(unlocks);
    }

    /** True when nothing has ever been recorded. */
    public boolean empty() {
        return counters.isEmpty() && earnedAt.isEmpty() && claimed.isEmpty() && unlocks.isEmpty();
    }

    private static String key(final String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
