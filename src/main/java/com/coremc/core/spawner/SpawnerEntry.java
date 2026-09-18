package com.coremc.core.spawner;

import java.util.UUID;

/**
 * One placed, registered CoreMC spawner. Identity is the block
 * position (one registration per block).
 *
 * @param world    world name
 * @param x        block x
 * @param y        block y
 * @param z        block z
 * @param mobId    mob config id (e.g. {@code pig})
 * @param variant  current variant
 * @param islandId the island the spawner belongs to (luck + credit)
 */
public record SpawnerEntry(String world, int x, int y, int z, String mobId,
                           SpawnerVariant variant, UUID islandId) {

    /** Stable map key for the block position. */
    public String key() {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
