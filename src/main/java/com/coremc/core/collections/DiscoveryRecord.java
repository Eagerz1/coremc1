package com.coremc.core.collections;

/**
 * Discovery state for one entry: whether it has ever been found, how
 * many have been found, and when the first one was.
 *
 * @param count how many have been found (0 = undiscovered)
 * @param first epoch millis of the first discovery (0 when never found)
 */
public record DiscoveryRecord(long count, long first) {

    /** Nothing found yet. */
    public static final DiscoveryRecord NONE = new DiscoveryRecord(0, 0);

    /** True once the player has found at least one. */
    public boolean discovered() {
        return count > 0;
    }

    /** The record after finding {@code amount} more at {@code now}. */
    public DiscoveryRecord plus(final long amount, final long now) {
        final long added = Math.max(0, amount);
        if (added == 0) {
            return this;
        }
        return new DiscoveryRecord(count + added, first == 0 ? now : first);
    }
}
