package com.coremc.core.role;

/**
 * Pure progression math for roles and the OmniTool — no Bukkit
 * dependencies so everything is unit-testable.
 *
 * Curve: xp required to advance from level L to L+1 is
 * {@code base * L^exponent}, rounded. Levels are capped; XP beyond
 * the cap is discarded.
 */
public final class ProgressionService {

    /** Default tuning (config.yml may scale gains via a multiplier, not the curve shape). */
    public static final int ROLE_BASE_XP = 100;
    public static final int TOOL_BASE_XP = 50;
    public static final double EXPONENT = 1.5;
    public static final int MAX_LEVEL = 50;

    /** Outcome of an award: mutable counters + what changed. */
    public static final class Result {
        public int level;
        public long xp;
        public int levelsGained;
        public boolean capped;

        Result(final int level, final long xp) {
            this.level = level;
            this.xp = xp;
        }
    }

    /** XP required to go from {@code level} to {@code level + 1}. */
    public long xpForNext(final int base, final int level) {
        if (level >= MAX_LEVEL) {
            return Long.MAX_VALUE;
        }
        return Math.round(base * Math.pow(level, EXPONENT));
    }

    /**
     * Awards {@code amount} XP to (level, xp) with the given curve base.
     * Never called with negative amounts by the listeners (validated here too).
     */
    public Result award(final int base, final int level, final long xp, final long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("XP amount must be >= 0, got " + amount);
        }
        final Result result = new Result(level, Math.max(0, xp));
        if (amount == 0 || level >= MAX_LEVEL) {
            result.capped = level >= MAX_LEVEL;
            if (result.capped) {
                result.xp = 0;
            }
            return result;
        }
        result.xp += amount;
        while (result.level < MAX_LEVEL) {
            final long needed = xpForNext(base, result.level);
            if (result.xp < needed) {
                break;
            }
            result.xp -= needed;
            result.level++;
            result.levelsGained++;
        }
        if (result.level >= MAX_LEVEL) {
            result.capped = true;
            result.xp = 0;
        }
        return result;
    }
}
