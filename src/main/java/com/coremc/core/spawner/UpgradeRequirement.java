package com.coremc.core.spawner;

import com.coremc.core.essence.EssenceType;

/**
 * One typed requirement of a spawner upgrade, data-driven from
 * spawners.yml. Amounts scale per spawner in a stack (a 2x stack pays
 * twice). {@code KILLS} is a lifetime threshold — it is checked but
 * never spent; the other types are consumed on a successful upgrade.
 *
 * <pre>
 * - {type: money,   amount: 5000}
 * - {type: kills,   amount: 10000}
 * - {type: essence, essence: slayer, amount: 20}
 * - {type: drop,    mob: pig, amount: 3}   # mob defaults to the mob being upgraded
 * </pre>
 *
 * @param type    what is required
 * @param essence essence type for {@link Type#ESSENCE}, else null
 * @param mobId   whose unique drop for {@link Type#DROP} (null = own)
 * @param amount  how much (scaled by the stack)
 */
public record UpgradeRequirement(Type type, EssenceType essence, String mobId, double amount) {

    /** Requirement kinds, in display order. */
    public enum Type {
        MONEY("Vault Balance"),
        KILLS("Mob Kills"),
        ESSENCE(""),
        DROP("Unique Drop");

        private final String tag;

        Type(final String tag) {
            this.tag = tag;
        }

        /** Short parenthetical used in lore lines, or "". */
        public String tag() {
            return tag;
        }

        /** Type for a config key, or null. */
        public static Type of(final String key) {
            if (key == null) {
                return null;
            }
            for (final Type type : values()) {
                if (type.name().equalsIgnoreCase(key.trim())) {
                    return type;
                }
            }
            return null;
        }
    }

    public UpgradeRequirement {
        // amount validation happens at parse time; runtime amounts stay as-is
    }

    public static UpgradeRequirement money(final double amount) {
        return new UpgradeRequirement(Type.MONEY, null, null, amount);
    }

    public static UpgradeRequirement kills(final double amount) {
        return new UpgradeRequirement(Type.KILLS, null, null, amount);
    }

    public static UpgradeRequirement essence(final EssenceType essence, final double amount) {
        return new UpgradeRequirement(Type.ESSENCE, essence, null, amount);
    }

    /** A requirement of a mob's unique drop ({@code mobId} null = the mob being upgraded). */
    public static UpgradeRequirement drop(final String mobId, final double amount) {
        return new UpgradeRequirement(Type.DROP, null, mobId, amount);
    }

    /** Whole-number amount (everything but money is integral). */
    public long wholeAmount() {
        return Math.round(amount);
    }

    /** True when this requirement is spent on success (kills are a threshold, not spent). */
    public boolean consumed() {
        return type != Type.KILLS;
    }
}
