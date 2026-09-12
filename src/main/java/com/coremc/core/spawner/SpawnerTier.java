package com.coremc.core.spawner;

import com.coremc.core.util.ColorUtil;

/**
 * One purchasable spawner of a mob's progression lane. Mob kill progress
 * unlocks tiers left to right (each tier is strictly "better": more
 * spawns per cycle and/or a faster cycle), and every unlocked tier stays
 * buyable with Sky Tokens.
 *
 * The final tier of every lane is the ANCIENT variant
 * ({@code ancient == true}): instead of a faster spawner it awakens a
 * single named, toughened Ancient mob per cycle. Killing an Ancient mob
 * grants a single kill event worth {@code spawners.ancient.progress-bonus}
 * (default 3) progress toward the NEXT mob lane — it is three PROGRESS,
 * never three instant kills: one rolling-cap slot, one death, one
 * message.
 *
 * {@code spawnCount} and {@code spawnDelayTicks} are applied to the
 * vanilla spawner block on placement — that is what "better" means in
 * worlds, not markup.
 */
public record SpawnerTier(
        String tierId,
        int index, // 1-based position in the mob lane (I..IV, Ancient last)
        String display,
        long requiredKills,
        long priceSkyTokens,
        int spawnCount,
        int spawnDelayTicks,
        boolean ancient) {

    /** Backwards-compatible constructor: non-ancient tier. */
    public SpawnerTier(
            final String tierId, final int index, final String display,
            final long requiredKills, final long priceSkyTokens,
            final int spawnCount, final int spawnDelayTicks) {
        this(tierId, index, display, requiredKills, priceSkyTokens, spawnCount, spawnDelayTicks, false);
    }

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
            case 6 -> "VI";
            default -> String.valueOf(index);
        };
    }

    /** Legacy-coloured display name. */
    public String colouredDisplay() {
        return ColorUtil.colorize(display);
    }

    /** Human-readable cycle summary for item lore. */
    public String throughputLine() {
        if (ancient) {
            return "awakens an Ancient mob every ~" + Math.max(1, spawnDelayTicks / 20) + "s";
        }
        return "spawns " + spawnCount + " every ~" + Math.max(1, spawnDelayTicks / 20) + "s";
    }

    /** Lore tail describing the tier's special behaviour (empty for standard tiers). */
    public String specialLine() {
        return ancient
                ? "&5Ancient kill grants triple progress toward the next mob"
                : "";
    }

    /** Stable purchasable/placeable id for a mob lane + index ("zombie-2"). */
    public static String tierId(final String mobId, final int index) {
        return mobId + "-" + index;
    }
}
