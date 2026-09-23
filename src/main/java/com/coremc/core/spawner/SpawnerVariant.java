package com.coremc.core.spawner;

/** The four spawner tiers, in upgrade order. */
public enum SpawnerVariant {

    NORMAL("Normal"),
    ADVANCED("Advanced"),
    ANCIENT("Ancient"),
    MYTHIC("Mythic");

    private final String display;

    SpawnerVariant(final String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    /** The next variant up, or null at Mythic. */
    public SpawnerVariant next() {
        final SpawnerVariant[] values = values();
        final int ordinal = ordinal();
        return ordinal < values.length - 1 ? values[ordinal + 1] : null;
    }

    public static SpawnerVariant of(final String name) {
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }
}
