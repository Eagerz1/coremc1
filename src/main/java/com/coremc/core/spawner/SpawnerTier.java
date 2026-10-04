package com.coremc.core.spawner;

import com.coremc.core.util.ColorUtil;

/**
 * The one regular purchasable spawner for a mob. The historical class
 * name and {@code mob-1} ids remain stable so existing items and placed
 * blocks continue to load after removal of spawner variants.
 *
 * {@code spawnCount} and {@code spawnDelayTicks} are applied to the
 * vanilla spawner block on placement — that is what "better" means in
 * worlds, not markup.
 */
public record SpawnerTier(
        String tierId,
        int index, // retained as 1 for stable historical item ids
        String display,
        long requiredKills,
        long priceSkyTokens,
        int spawnCount,
        int spawnDelayTicks) {

    public SpawnerTier {
        if (tierId == null || tierId.isBlank()) {
            throw new IllegalArgumentException("Spawner tier id must not be blank");
        }
        if (index < 1) {
            throw new IllegalArgumentException("Spawner tier '" + tierId + "' index must be >= 1");
        }
        if (requiredKills < 0L) {
            throw new IllegalArgumentException("Spawner tier '" + tierId + "' required-kills < 0");
        }
        if (priceSkyTokens < 0L) {
            throw new IllegalArgumentException("Spawner tier '" + tierId + "' price < 0");
        }
        if (spawnCount < 1 || spawnCount > 8) {
            throw new IllegalArgumentException("Spawner tier '" + tierId + "' spawn-count must be 1..8");
        }
        if (spawnDelayTicks < 20 || spawnDelayTicks > 20 * 600) {
            throw new IllegalArgumentException(
                    "Spawner tier '" + tierId + "' spawn-delay must be 1s..600s in ticks");
        }
    }

    /** Legacy-coloured display name. */
    public String colouredDisplay() {
        return ColorUtil.colorize(display);
    }

    /** Human-readable cycle summary for item lore. */
    public String throughputLine() {
        return "spawns " + spawnCount + " every ~" + Math.max(1, spawnDelayTicks / 20) + "s";
    }

    /** Stable purchasable/placeable id for a mob lane + index ("zombie-2"). */
    public static String tierId(final String mobId, final int index) {
        return mobId + "-" + index;
    }
}
