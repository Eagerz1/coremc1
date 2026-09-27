package com.coremc.core.achievements;

import java.util.Locale;

/** How an Achievement counts the events it listens to. */
public enum AchievementMode {

    /** Sum every amount, e.g. "mine 100,000 blocks". */
    TOTAL("total"),
    /** Keep the highest value ever reported, e.g. "reach island level 25". */
    MAX("max"),
    /** Count distinct subject keys, e.g. "kill 15 different mob types". */
    UNIQUE("unique");

    private final String id;

    AchievementMode(final String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Mode for a config id (case-insensitive), or null. */
    public static AchievementMode of(final String id) {
        if (id == null) {
            return null;
        }
        final String needle = id.trim().toLowerCase(Locale.ROOT);
        for (final AchievementMode mode : values()) {
            if (mode.id.equals(needle)) {
                return mode;
            }
        }
        return null;
    }
}
