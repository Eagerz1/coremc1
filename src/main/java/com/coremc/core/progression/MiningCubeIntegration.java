package com.coremc.core.progression;

import com.coremc.core.island.Island;
import org.bukkit.Location;

/**
 * Integration seam for the Mining Cube branch. This branch does not contain a
 * Mining Cube implementation yet; future branches should register an adapter
 * here instead of duplicating Rich Vein logic.
 */
public interface MiningCubeIntegration {

    /** True only for blocks generated/owned by the Mining Cube system. */
    boolean isMiningCubeBlock(Island island, Location location);

    /** Spawn a temporary vein formation in the cube. */
    default void spawnRichVein(final Island island, final Location origin,
                               final IslandCoreBuffConfig.BuffDef buff,
                               final int size) {
        // no-op until Mining Cube implementation is present
    }

    static MiningCubeIntegration none() {
        return (island, location) -> false;
    }
}
