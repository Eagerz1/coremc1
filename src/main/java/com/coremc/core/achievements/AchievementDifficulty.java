package com.coremc.core.achievements;

import java.util.Locale;

/**
 * Difficulty tiers and their default Achievement Point values.
 *
 * <p>Points are <b>prestige only</b>: they are a permanent score shown
 * on the profile and used for ranking, and they can never be spent on
 * anything. That keeps them meaningful forever and stops Achievements
 * turning into a second currency.</p>
 */
public enum AchievementDifficulty {

    COMMON("common", "Common", 5, "&a"),
    RARE("rare", "Rare", 15, "&9"),
    EPIC("epic", "Epic", 30, "&5"),
    LEGENDARY("legendary", "Legendary", 50, "&6"),
    PRESTIGE("prestige", "Prestige", 100, "&c");

    private final String id;
    private final String display;
    private final int defaultPoints;
    private final String color;

    AchievementDifficulty(final String id, final String display, final int defaultPoints,
                          final String color) {
        this.id = id;
        this.display = display;
        this.defaultPoints = defaultPoints;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    /** Points awarded unless achievements.yml overrides them. */
    public int defaultPoints() {
        return defaultPoints;
    }

    public String color() {
        return color;
    }

    /** Difficulty for a config id (case-insensitive), or null. */
    public static AchievementDifficulty of(final String id) {
        if (id == null) {
            return null;
        }
        final String needle = id.trim().toLowerCase(Locale.ROOT);
        for (final AchievementDifficulty difficulty : values()) {
            if (difficulty.id.equals(needle)) {
                return difficulty;
            }
        }
        return null;
    }
}
