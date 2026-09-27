package com.coremc.core.season;

/** Stable XP sources for the Season Journey API. */
public enum SeasonXpSource {
    DAILY_QUEST,
    WEEKLY_QUEST,
    ISLAND_CHALLENGE,
    ISLAND_MILESTONE,
    MASTERY_MILESTONE,
    CORE_UNLOCK,
    ROLE_MILESTONE,
    OMNITOOL_MILESTONE,
    MINING_MILESTONE,
    FARMING_MILESTONE,
    FISHING_MILESTONE,
    SLAYER_MILESTONE,
    GENERATOR_MILESTONE,
    EVENT,
    ADMIN;

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public static SeasonXpSource parse(final String raw) {
        final String id = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        return SeasonXpSource.valueOf(id);
    }
}
