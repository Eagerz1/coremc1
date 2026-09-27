package com.coremc.core.progress;

import java.util.Locale;

/**
 * Where an authoritative progression event came from. The source is
 * part of the anti-exploit policy: Collections can require that mining
 * progress comes from generated/Mining Cube blocks rather than from
 * blocks a player placed a second ago, and admin grants are always
 * recorded as such.
 */
public enum ProgressSource {

    /** A normal world interaction (validated by the guards). */
    WORLD("world"),
    /** The Mining Cube / generated mining progression (adapter). */
    MINING_CUBE("mining_cube"),
    /** A CoreMC generator block. */
    GENERATOR("generator"),
    /** A CoreMC spawner. */
    SPAWNER("spawner"),
    /** Fishing. */
    FISHING("fishing"),
    /** An island discovery. */
    DISCOVERY("discovery"),
    /** Quest system (adapter). */
    QUEST("quest"),
    /** Hourly/world events (adapter). */
    EVENT("event"),
    /** Season Journey (adapter). */
    SEASON("season"),
    /** Roles / OmniTools (adapter). */
    ROLE("role"),
    /** Companions (adapter). */
    COMPANION("companion"),
    /** A staff grant — always logged, never silent. */
    ADMIN("admin");

    private final String id;

    ProgressSource(final String id) {
        this.id = id;
    }

    /** Stable config/storage id. */
    public String id() {
        return id;
    }

    /** Source for a config id (case-insensitive), or null. */
    public static ProgressSource of(final String id) {
        if (id == null) {
            return null;
        }
        final String needle = id.trim().toLowerCase(Locale.ROOT);
        for (final ProgressSource source : values()) {
            if (source.id.equals(needle)) {
                return source;
            }
        }
        return null;
    }
}
