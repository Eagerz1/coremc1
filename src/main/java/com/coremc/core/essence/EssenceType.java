package com.coremc.core.essence;

/**
 * The three virtual essence currencies, earned by actively playing:
 * Slayer for manual mob kills, Mining for breaking natural resource
 * blocks, Farming for harvesting fully grown crops.
 *
 * <p>These are account balances, never items — the physical group
 * essences of the old spawner system were removed and replaced by
 * this one.</p>
 */
public enum EssenceType {

    SLAYER("Slayer Essence", "slayer"),
    MINING("Mining Essence", "mining"),
    FARMING("Farming Essence", "farming");

    private final String display;
    private final String key;

    EssenceType(final String display, final String key) {
        this.display = display;
        this.key = key;
    }

    /** Player-facing name, e.g. "Slayer Essence". */
    public String display() {
        return display;
    }

    /** Config/command key, e.g. "slayer". */
    public String key() {
        return key;
    }

    /** Type for a config/command key (case-insensitive), or null. */
    public static EssenceType of(final String key) {
        if (key == null) {
            return null;
        }
        for (final EssenceType type : values()) {
            if (type.key.equalsIgnoreCase(key.trim())) {
                return type;
            }
        }
        return null;
    }
}
