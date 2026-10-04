package com.coremc.core.companion;

/** Pure level/xp math for companions. */
public final class CompanionProgression {

    public record Result(int level, long xp, int levelsGained) {
    }

    private CompanionProgression() {
    }

    public static long xpToNext(final int level) {
        return 100L * Math.max(1, level);
    }

    public static Result award(final int currentLevel, final long currentXp,
            final long amount, final int maxLevel) {
        int level = Math.max(1, Math.min(currentLevel, maxLevel));
        long xp = Math.max(0L, currentXp) + Math.max(0L, amount);
        final int before = level;
        while (level < maxLevel && xp >= xpToNext(level)) {
            xp -= xpToNext(level);
            level++;
        }
        if (level >= maxLevel) {
            xp = 0L;
        }
        return new Result(level, xp, level - before);
    }
}
