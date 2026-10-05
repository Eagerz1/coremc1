package com.coremc.core.island;

import java.util.List;

/**
 * Season-one island progression track and mastery objectives.
 *
 * XP thresholds retain the existing square-root pace, but the seasonal
 * track ends at level 30. Activity objectives use the island lifetime
 * counters already persisted by IslandProgressService.
 */
public final class IslandProgressionCatalog {

    public static final int MAX_LEVEL = 30;
    private static final List<Level> LEVELS = List.of(
            new Level(1, "First Steps", List.of("Starter island")),
            new Level(2, "Settler", List.of("Island upgrades")),
            new Level(3, "Generator Route", List.of("Improved generators")),
            new Level(4, "Resource Chain", List.of("More generator options")),
            new Level(5, "Slayer Route", List.of("Second slaying section")),
            new Level(6, "Island Builder", List.of("Expanded island border")),
            new Level(7, "Tool Specialist", List.of("Role tool progression")),
            new Level(8, "Companion Keeper", List.of("Companions")),
            new Level(9, "Daily Routine", List.of("Daily missions")),
            new Level(10, "Set Crafter", List.of("Role equipment sets")),
            new Level(11, "Deep Miner", List.of("Advanced mining upgrades")),
            new Level(12, "Green Thumb", List.of("Advanced farming upgrades")),
            new Level(13, "Treasure Hunter", List.of("Improved rare-drop chances")),
            new Level(14, "Tidecaller", List.of("Advanced fishing upgrades")),
            new Level(15, "Spawner Expert", List.of("Higher spawner tiers")),
            new Level(16, "Generator Engineer", List.of("Top generator tiers")),
            new Level(17, "Role Master", List.of("Role mastery rewards")),
            new Level(18, "Rare Finds", List.of("Rare-drop milestone")),
            new Level(19, "Island Tactician", List.of("Advanced island buffs")),
            new Level(20, "Mastery Seeker", List.of("Mastery reward tier II")),
            new Level(21, "Season Contractor", List.of("Long-term contracts")),
            new Level(22, "Companion Trainer", List.of("Higher companion growth")),
            new Level(23, "Elite Slayer", List.of("Elite slaying section")),
            new Level(24, "Market Specialist", List.of("Special market rewards")),
            new Level(25, "Relic Hunter", List.of("Rare-drop bonus")),
            new Level(26, "Core Hour Veteran", List.of("Core Hour milestone")),
            new Level(27, "Island Legacy", List.of("Legacy cosmetic reward")),
            new Level(28, "Mythic Path", List.of("Mythic progression reward")),
            new Level(29, "Season Champion", List.of("Season champion cosmetic")),
            new Level(30, "Season Complete", List.of("Season completion reward")));

    private static final List<Mastery> MASTERY = List.of(
            new Mastery("miner", "Ore Breaker", "blocks-mined", 25_000L),
            new Mastery("slayer", "Mob Hunter", "mobs-killed", 5_000L),
            new Mastery("farmer", "Harvest Keeper", "crops-harvested", 25_000L),
            new Mastery("logger", "Forest Keeper", "logs-chopped", 10_000L),
            new Mastery("fisher", "Deep Catch", "fish-caught", 2_500L),
            new Mastery("generator", "Core Operator", "generator-harvests", 5_000L));

    private IslandProgressionCatalog() {}

    public static List<Level> levels() {
        return LEVELS;
    }

    public static Level level(final int level) {
        return LEVELS.get(Math.max(1, Math.min(MAX_LEVEL, level)) - 1);
    }

    public static List<Mastery> masteryObjectives() {
        return MASTERY;
    }

    /** Existing curve, capped to the 30-level season track. */
    public static int levelForScore(final long score, final long divisor) {
        if (score <= 0L || divisor <= 0L) {
            return 1;
        }
        final double raw = 1.0 + Math.floor(Math.sqrt(score / (double) divisor));
        return (int) Math.min(MAX_LEVEL, raw);
    }

    /** Score threshold for a level under the existing square-root curve. */
    public static long requiredScore(final int level, final long divisor) {
        if (level <= 1 || divisor <= 0L) {
            return 0L;
        }
        final long steps = Math.min(MAX_LEVEL - 1L, level - 1L);
        return divisor * steps * steps;
    }

    public record Level(int number, String title, List<String> unlocks) {
        public Level {
            unlocks = List.copyOf(unlocks);
        }
    }

    public record Mastery(String id, String title, String statKey, long target) {}
}
