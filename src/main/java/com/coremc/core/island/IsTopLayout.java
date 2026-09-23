package com.coremc.core.island;

import java.util.List;

/**
 * Pure slot maths and reward tiers for the island top GUIs.
 *
 * <p>Picker (small chest, 27): three category options spaced across the
 * middle row, close bottom-right.</p>
 * <pre>
 * [4] how-points-work book
 * [10] Solos   [13] Duos   [16] Teams
 * [22] close
 * </pre>
 *
 * <p>Board (double chest, 54): ten islands in two centred rows — ranks
 * 1-5 on slots 10-14, ranks 6-10 on slots 19-23 — with back / your
 * rank / close along the bottom.</p>
 * <pre>
 * [10..14]  ranks 1-5
 * [19..23]  ranks 6-10
 * [45] back  [49] your rank  [53] close
 * </pre>
 */
public final class IsTopLayout {

    /** The category picker is a small chest. */
    public static final int PICKER_SIZE = 27;
    /** The leaderboard boards are double chests. */
    public static final int BOARD_SIZE = 54;

    public static final int PICKER_INFO = 4;
    public static final int PICKER_SOLOS = 10;
    public static final int PICKER_DUOS = 13;
    public static final int PICKER_TEAMS = 16;
    public static final int PICKER_CLOSE = 22;

    public static final int BOARD_FIRST_ROW = 10;
    public static final int BOARD_SECOND_ROW = 19;
    /** Ranks per board row (ten teams shown in total). */
    public static final int RANKS_PER_ROW = 5;
    /** Total teams displayed per board. */
    public static final int BOARD_ENTRIES = 10;

    public static final int BOARD_BACK = 45;
    public static final int BOARD_YOUR_RANK = 49;
    public static final int BOARD_CLOSE = 53;

    /**
     * Gift card rewards (webstore credit) per category, paid to the
     * island owner. Teams pay five places, Duos three, Solos five.
     */
    private static final List<Double> SOLO_REWARDS = List.of(100.0, 75.0, 50.0, 30.0, 25.0);
    private static final List<Double> DUO_REWARDS = List.of(100.0, 75.0, 50.0);
    private static final List<Double> TEAM_REWARDS = List.of(100.0, 75.0, 50.0, 35.0, 25.0);

    /** Picker slot of a category option. */
    public static int pickerSlot(final IslandTop.Category category) {
        return switch (category) {
            case SOLOS -> PICKER_SOLOS;
            case DUOS -> PICKER_DUOS;
            case TEAMS -> PICKER_TEAMS;
        };
    }

    /** The category a picker slot holds, or null. */
    public static IslandTop.Category pickerCategoryAt(final int slot) {
        return switch (slot) {
            case PICKER_SOLOS -> IslandTop.Category.SOLOS;
            case PICKER_DUOS -> IslandTop.Category.DUOS;
            case PICKER_TEAMS -> IslandTop.Category.TEAMS;
            default -> null;
        };
    }

    /** Board slot for rank {@code rank} (1-based), or -1 when off-board. */
    public static int boardSlot(final int rank) {
        if (rank < 1 || rank > BOARD_ENTRIES) {
            return -1;
        }
        return rank <= RANKS_PER_ROW
                ? BOARD_FIRST_ROW + (rank - 1)
                : BOARD_SECOND_ROW + (rank - RANKS_PER_ROW - 1);
    }

    /** The reward list of a category (immutable). */
    public static List<Double> rewards(final IslandTop.Category category) {
        return switch (category) {
            case SOLOS -> SOLO_REWARDS;
            case DUOS -> DUO_REWARDS;
            case TEAMS -> TEAM_REWARDS;
        };
    }

    /** Gift card reward for a rank (1-based) in a category; 0 = none. */
    public static double rewardFor(final IslandTop.Category category, final int rank) {
        final List<Double> rewards = rewards(category);
        if (rank < 1 || rank > rewards.size()) {
            return 0.0;
        }
        return rewards.get(rank - 1);
    }

    /** The lowest rewarded rank of a category (e.g. "top 5 win rewards"). */
    public static int rewardedPlaces(final IslandTop.Category category) {
        return rewards(category).size();
    }

    private IsTopLayout() {
    }
}
