package com.coremc.core.island;

import java.util.List;
import java.util.Optional;

/**
 * The season's fixed 30-level island progression catalog.
 *
 * XP thresholds retain the existing square-root curve so existing island XP
 * and stats remain valid; the catalog adds stable unlock identifiers that
 * later progression systems can consume without relying on display text.
 */
public final class IslandLevelTrack {

    public static final int MAX_LEVEL = 30;

    public record Level(int level, long requiredScore, String title, List<String> unlocks) {
        public Level {
            unlocks = List.copyOf(unlocks);
        }
    }

    private static final List<String> TITLES = List.of(
            "New Roots", "Island Builder", "Growing Strong", "Steady Hands", "Mob Hunter",
            "Farmstead", "Deep Miner", "Companion Keeper", "Island Specialist", "Set Collector",
            "Established", "Seasoned", "Core of the Island", "Master Builder", "Spawner Expert",
            "Skybound", "Veteran", "Efficient Island", "Island Authority", "Core Keeper",
            "Elite", "Sky Architect", "Season Champion", "Island Legend", "Core Champion",
            "Ascendant", "Sky Sovereign", "Master of the Isles", "Season Pinnacle", "CoreMC Legend");

    private static final List<List<String>> UNLOCKS = List.of(
            List.of("starter-island"),
            List.of(),
            List.of("generator-tier-2"),
            List.of(),
            List.of("slayer-section-2"),
            List.of(),
            List.of("generator-tier-3"),
            List.of("companions"),
            List.of(),
            List.of("equipment-sets"),
            List.of(),
            List.of("island-mastery"),
            List.of(),
            List.of("generator-tier-4"),
            List.of("advanced-spawners"),
            List.of(),
            List.of("weekly-missions"),
            List.of(),
            List.of("mastery-rewards"),
            List.of(),
            List.of("season-cosmetics"),
            List.of("generator-tier-5"),
            List.of(),
            List.of("slayer-section-3"),
            List.of(),
            List.of("advanced-equipment"),
            List.of(),
            List.of("season-milestones"),
            List.of(),
            List.of("season-finale"));

    private IslandLevelTrack() {}

    /** Immutable level definitions using the configured XP divisor. */
    public static List<Level> levels(final long xpDivisor) {
        final long divisor = Math.max(1L, xpDivisor);
        final java.util.ArrayList<Level> result = new java.util.ArrayList<>(MAX_LEVEL);
        for (int level = 1; level <= MAX_LEVEL; level++) {
            final long distance = level - 1L;
            final long square = distance * distance;
            final long required = square > Long.MAX_VALUE / divisor ? Long.MAX_VALUE : square * divisor;
            result.add(new Level(level, required, TITLES.get(level - 1), UNLOCKS.get(level - 1)));
        }
        return List.copyOf(result);
    }

    /** The highest level reached at the given score, clamped to the season track. */
    public static int levelForScore(final long score, final long xpDivisor) {
        if (score <= 0L || xpDivisor <= 0L) {
            return 1;
        }
        final int level = 1 + (int) Math.floor(Math.sqrt(score / (double) xpDivisor));
        return Math.min(MAX_LEVEL, Math.max(1, level));
    }

    /** The definition for a level, if it belongs to the season track. */
    public static Optional<Level> level(final int level, final long xpDivisor) {
        if (level < 1 || level > MAX_LEVEL) {
            return Optional.empty();
        }
        return Optional.of(levels(xpDivisor).get(level - 1));
    }
}
