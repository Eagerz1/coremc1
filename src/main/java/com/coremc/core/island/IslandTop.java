package com.coremc.core.island;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Pure ranking maths for the island top leaderboards. Islands compete
 * in three categories by team size — Solos (1 member), Duos (2) and
 * Teams (3+) — and rank by island points, oldest island first on ties.
 */
public final class IslandTop {

    /** The three leaderboards. */
    public enum Category {
        SOLOS("Solos"),
        DUOS("Duos"),
        TEAMS("Teams");

        private final String display;

        Category(final String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    /**
     * The category an island competes in: by current team size
     * (owner + members), so inviting a third member moves the island
     * from Duos to Teams.
     */
    public static Category categoryOf(final Island island) {
        final int teamSize = 1 + island.members().size();
        if (teamSize <= 1) {
            return Category.SOLOS;
        }
        return teamSize == 2 ? Category.DUOS : Category.TEAMS;
    }

    /**
     * The {@code limit} best islands of {@code category}, most points
     * first (ties: the older island wins — it earned them first).
     */
    public static List<Island> top(final List<Island> islands,
                                   final ToDoubleFunction<Island> points,
                                   final Category category, final int limit) {
        return islands.stream()
                .filter(island -> categoryOf(island) == category)
                .sorted(Comparator
                        .comparingDouble((Island island) -> points.applyAsDouble(island)).reversed()
                        .thenComparingLong(Island::createdAt))
                .limit(Math.max(0, limit))
                .toList();
    }

    /**
     * The island's rank (1-based) in its category, or -1 when it is
     * not in the candidate list.
     */
    public static int rankOf(final Island island, final List<Island> islands,
                             final ToDoubleFunction<Island> points) {
        final List<Island> ranked = islands.stream()
                .filter(other -> categoryOf(other) == categoryOf(island))
                .sorted(Comparator
                        .comparingDouble((Island other) -> points.applyAsDouble(other)).reversed()
                        .thenComparingLong(Island::createdAt))
                .toList();
        final int index = ranked.indexOf(island);
        return index < 0 ? -1 : index + 1;
    }

    private IslandTop() {
    }
}
