package com.coremc.core.collections;

import java.util.Collection;
import java.util.List;

/**
 * The Collection maths, as pure functions: tiers reached, the next
 * milestone, per-entry progress and completion percentages.
 *
 * <p>Deterministic by design — the same amounts always give the same
 * percentage, percentages are floored to whole numbers so a 99.6%
 * Collection never claims to be complete, and an entry with no
 * milestones contributes nothing instead of dividing by zero.</p>
 */
public final class CollectionProgress {

    private CollectionProgress() {
    }

    /** How many milestone tiers {@code amount} has reached. */
    public static int tiersReached(final long amount, final List<CollectionMilestone> milestones) {
        if (milestones == null || milestones.isEmpty()) {
            return 0;
        }
        int reached = 0;
        for (final CollectionMilestone milestone : milestones) {
            if (amount >= milestone.amount()) {
                reached++;
            } else {
                break;
            }
        }
        return reached;
    }

    /** The next milestone to reach, or null when the entry is complete. */
    public static CollectionMilestone next(final long amount,
                                           final List<CollectionMilestone> milestones) {
        if (milestones == null) {
            return null;
        }
        for (final CollectionMilestone milestone : milestones) {
            if (amount < milestone.amount()) {
                return milestone;
            }
        }
        return null;
    }

    /** True when every tier of the entry has been reached. */
    public static boolean complete(final long amount, final List<CollectionMilestone> milestones) {
        return milestones != null && !milestones.isEmpty()
                && tiersReached(amount, milestones) == milestones.size();
    }

    /**
     * Progress towards the next tier as a 0..1 fraction (1.0 when the
     * entry is complete). Measured from the previous tier, so a bar
     * always fills across the tier the player is actually working on.
     */
    public static double tierFraction(final long amount, final List<CollectionMilestone> milestones) {
        final CollectionMilestone next = next(amount, milestones);
        if (next == null) {
            return 1.0;
        }
        final int reached = tiersReached(amount, milestones);
        final long previous = reached == 0 ? 0 : milestones.get(reached - 1).amount();
        final long span = next.amount() - previous;
        if (span <= 0) {
            return 1.0;
        }
        return clamp((double) (amount - previous) / span);
    }

    /**
     * Completion of one entry as a 0..1 fraction: the share of its
     * tiers that are done. Entries are worth the same regardless of
     * how many tiers they have, so a ten-tier grind cannot drown out
     * the rest of a category.
     */
    public static double entryCompletion(final long amount, final CollectionEntry entry) {
        if (entry == null || entry.tiers() == 0) {
            return 0.0;
        }
        return clamp((double) tiersReached(amount, entry.milestones()) / entry.tiers());
    }

    /**
     * Completion of a set of entries as a 0..1 fraction: the mean of
     * their entry completions.
     */
    public static double completion(final Collection<CollectionEntry> entries,
                                    final java.util.function.ToLongFunction<CollectionEntry> amounts) {
        if (entries == null || entries.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (final CollectionEntry entry : entries) {
            sum += entryCompletion(amounts.applyAsLong(entry), entry);
        }
        return clamp(sum / entries.size());
    }

    /** A 0..1 fraction as a floored whole percentage (0..100). */
    public static int percent(final double fraction) {
        return (int) Math.floor(clamp(fraction) * 100.0);
    }

    /** How many of the entries are fully complete. */
    public static int completedCount(final Collection<CollectionEntry> entries,
                                     final java.util.function.ToLongFunction<CollectionEntry> amounts) {
        if (entries == null) {
            return 0;
        }
        int done = 0;
        for (final CollectionEntry entry : entries) {
            if (complete(amounts.applyAsLong(entry), entry.milestones())) {
                done++;
            }
        }
        return done;
    }

    /** How many of the entries have been started (or discovered). */
    public static int discoveredCount(final Collection<CollectionEntry> entries,
                                      final java.util.function.ToLongFunction<CollectionEntry> amounts) {
        if (entries == null) {
            return 0;
        }
        int seen = 0;
        for (final CollectionEntry entry : entries) {
            if (amounts.applyAsLong(entry) > 0) {
                seen++;
            }
        }
        return seen;
    }

    private static double clamp(final double value) {
        if (Double.isNaN(value) || value < 0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
