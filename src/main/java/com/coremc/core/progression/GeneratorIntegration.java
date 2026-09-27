package com.coremc.core.progression;

import com.coremc.core.island.Island;

/**
 * Integration seam for the Generator branch. If a later Arena branch provides
 * native generators, register an adapter with IslandCoreBuffService so CoreMC
 * uses the same Overdrive state instead of a second generator system.
 */
public interface GeneratorIntegration {

    /** Number of eligible generators that accepted an overdrive activation. */
    default int activateOverdrive(final Island island, final IslandCoreBuffConfig.BuffDef buff,
                                  final long durationMillis, final double speedMultiplier) {
        return 0;
    }

    static GeneratorIntegration none() {
        return new GeneratorIntegration() {
        };
    }
}
