package com.coremc.core.essence;

import com.coremc.core.spawner.SpawnerVariant;

/**
 * Pure Slayer Essence kill maths, kept free of Bukkit events so the
 * earning rules are unit-testable: only kills a player actively
 * caused pay anything, and a killed mob stack counts as that many
 * mobs at the variant's rate.
 */
public final class SlayerRewards {

    private SlayerRewards() {
    }

    /**
     * Slayer Essence for one death.
     *
     * @param activePlayerKill true when a player directly caused the death
     *                         (melee or their own projectile) — automated
     *                         deaths (fall, lava, void, iron golems, Mythic
     *                         auto-kill, {@code /kill}) pay nothing
     * @param variant          the variant of the spawner the mob came from;
     *                         natural mobs count as Normal
     * @param mobCount         how many mobs this entity represented
     *                         (a killed "4x Pig" stack is 4)
     * @param config           the earning rules
     * @return the essence to award (0 unless an active player kill)
     */
    public static long slayerFor(final boolean activePlayerKill, final SpawnerVariant variant,
                                 final int mobCount, final EssenceConfig config) {
        if (!activePlayerKill || mobCount <= 0) {
            return 0L;
        }
        return config.slayerPerMob(variant == null ? SpawnerVariant.NORMAL : variant)
                * (long) mobCount;
    }

    /**
     * Mob-kill credit for one death: every mob in a killed stack counts,
     * but again only for active player kills.
     */
    public static long killsFor(final boolean activePlayerKill, final int mobCount) {
        return activePlayerKill ? Math.max(0, mobCount) : 0L;
    }
}
