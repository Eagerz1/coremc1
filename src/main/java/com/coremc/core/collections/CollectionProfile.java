package com.coremc.core.collections;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One player's permanent Collection state.
 *
 * <p>Everything here is keyed by stable ids, never by display names,
 * and none of it is seasonal: totals, discovery records, claimed
 * milestone rewards and permanent unlocks all survive a season reset
 * untouched.</p>
 */
public final class CollectionProfile {

    private final Map<String, Long> amounts = new LinkedHashMap<>();
    private final Map<String, DiscoveryRecord> discoveries = new LinkedHashMap<>();
    private final Set<String> claimed = new LinkedHashSet<>();
    private final Set<String> unlocks = new LinkedHashSet<>();

    /** Total collected for an entry. */
    public long amount(final String entryId) {
        return amounts.getOrDefault(key(entryId), 0L);
    }

    /** Adds to an entry's total and returns the new total. */
    public long add(final String entryId, final long delta) {
        if (delta <= 0) {
            return amount(entryId);
        }
        final String key = key(entryId);
        final long total = amounts.getOrDefault(key, 0L) + delta;
        amounts.put(key, total);
        return total;
    }

    /** Sets an exact total (admin tools, storage loading). */
    public void set(final String entryId, final long total) {
        final String key = key(entryId);
        if (total <= 0) {
            amounts.remove(key);
        } else {
            amounts.put(key, total);
        }
    }

    /** Every recorded total (read-only). */
    public Map<String, Long> amounts() {
        return Map.copyOf(amounts);
    }

    // ------------------------------------------------------------------
    // discoveries
    // ------------------------------------------------------------------

    /** Discovery state for an entry (never null). */
    public DiscoveryRecord discovery(final String entryId) {
        return discoveries.getOrDefault(key(entryId), DiscoveryRecord.NONE);
    }

    /** Records finding {@code amount} of a discovery; keeps the first timestamp. */
    public DiscoveryRecord discover(final String entryId, final long amount, final long now) {
        final String key = key(entryId);
        final DiscoveryRecord updated = discovery(key).plus(amount, now);
        discoveries.put(key, updated);
        return updated;
    }

    /** Restores a stored discovery record. */
    public void putDiscovery(final String entryId, final DiscoveryRecord record) {
        if (record != null && record.discovered()) {
            discoveries.put(key(entryId), record);
        }
    }

    /** Every discovery record (read-only). */
    public Map<String, DiscoveryRecord> discoveries() {
        return Map.copyOf(discoveries);
    }

    // ------------------------------------------------------------------
    // claims + unlocks
    // ------------------------------------------------------------------

    /** True when this milestone's manual reward was already claimed. */
    public boolean claimed(final String entryId, final int tier) {
        return claimed.contains(claimKey(entryId, tier));
    }

    /** Marks a milestone claimed; false when it already was (exactly-once guard). */
    public boolean claim(final String entryId, final int tier) {
        return claimed.add(claimKey(entryId, tier));
    }

    /** Restores a stored claim. */
    public void putClaim(final String raw) {
        if (raw != null && !raw.isBlank()) {
            claimed.add(raw.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** Every claim key (read-only). */
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

    /** Every unlock key (read-only). */
    public Set<String> unlocks() {
        return Set.copyOf(unlocks);
    }

    /** True when nothing has ever been recorded (used to skip empty saves). */
    public boolean empty() {
        return amounts.isEmpty() && discoveries.isEmpty() && claimed.isEmpty() && unlocks.isEmpty();
    }

    /** The storage key of a milestone claim. */
    public static String claimKey(final String entryId, final int tier) {
        return key(entryId) + ":" + Math.max(1, tier);
    }

    private static String key(final String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
