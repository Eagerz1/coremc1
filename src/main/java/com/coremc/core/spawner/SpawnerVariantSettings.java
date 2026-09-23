package com.coremc.core.spawner;

/**
 * Behaviour of one spawner variant: {@code rate} divides the base
 * spawn delay, {@code count} is mobs per cycle, {@code nearbyLimit}
 * caps live mobs around the spawner, and {@code autoKill} kills
 * spawns shortly after they appear (drops credited to the island
 * owner) — the Mythic grinder mode.
 */
public record SpawnerVariantSettings(double rate, int count, int nearbyLimit, boolean autoKill) {

    /** Scaled spawn delays in ticks: [min, max]. */
    public int[] delays(final int baseMin, final int baseMax) {
        final int min = Math.max(20, (int) Math.round(baseMin / rate));
        final int max = Math.max(min + 1, (int) Math.round(baseMax / rate));
        return new int[]{min, max};
    }
}
