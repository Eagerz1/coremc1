package com.coremc.core.reward;

import java.util.List;

/**
 * Weighted random selection over a reward pool. Pure maths, no server:
 * the same table backs the actual rolls and the preview GUIs' odds, so
 * shown chances can never drift from real chances.
 */
public final class WeightedTable {

    private final List<RewardDef> entries;
    private final double totalWeight;

    public WeightedTable(final List<RewardDef> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("a reward table needs at least one entry");
        }
        double total = 0;
        for (final RewardDef entry : entries) {
            if (entry.weight() <= 0 || !Double.isFinite(entry.weight())) {
                throw new IllegalArgumentException("reward '" + entry.id()
                        + "' has a non-positive weight (" + entry.weight() + ")");
            }
            total += entry.weight();
        }
        this.entries = List.copyOf(entries);
        this.totalWeight = total;
    }

    /** The entries, in configured order. */
    public List<RewardDef> entries() {
        return entries;
    }

    /** The sum of all weights. */
    public double totalWeight() {
        return totalWeight;
    }

    /**
     * Picks the entry a 0..1 roll lands on. Rolls at exactly 0 pick the
     * first entry; rolls at (or beyond) 1 clamp to the last — no roll
     * can ever fall through the table.
     */
    public RewardDef pick(final double roll01) {
        final double target = Math.min(Math.max(roll01, 0.0), 1.0) * totalWeight;
        double running = 0;
        for (final RewardDef entry : entries) {
            running += entry.weight();
            if (target < running) {
                return entry;
            }
        }
        return entries.get(entries.size() - 1);
    }

    /** The exact chance of one entry, in percent (shown by previews). */
    public double chancePercent(final RewardDef entry) {
        return entry.weight() / totalWeight * 100.0;
    }

    /** Formats a chance like {@code 12.5%} (two decimals max, trailing zeros trimmed). */
    public String chanceText(final RewardDef entry) {
        final double percent = chancePercent(entry);
        String text = String.format(java.util.Locale.US, "%.2f", percent);
        while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "%";
    }
}
