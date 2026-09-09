package com.coremc.core.spawner;

import com.coremc.core.util.ColorUtil;

/**
 * One purchasable spawner of a mob's progression lane. Mob kill progress
 * unlocks tiers left to right (each tier is strictly "better": more
 * spawns per cycle and/or a faster cycle), and every unlocked tier stays
 * buyable with Sky Tokens.
 *
 * {@code spawnCount} and {@code spawnDelayTicks} are applied to the
 * vanilla spawner block on placement — that is what "better" means in
 * worlds, not markup.
 */
public record SpawnerTier(
        String tierId,
        int index, // 1-based position in the mob lane (I..IV)
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

    /** Roman numeral lane label for menus (I, II, III, IV, …). */
    public static String roman(final int index) {
        return switch (index) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(index);
        };
    }

    /** Legacy-coloured display name. */
    public String colouredDisplay() {
        return ColorUtil.colorize(display);
    }

    /** Human-readable cycle summary for item lore: "spawns 2 every ~16s". */
    public String throughputLine() {
        return "spawns " + spawnCount + " every ~" + Math.max(1, spawnDelayTicks / 20) + "s";
    }

    /** Stable purchasable/placeable id for a mob lane + index ("zombie-2"). */
    public static String tierId(final String mobId, final int index) {
        return mobId + "-" + index;
    }
}
