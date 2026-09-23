package com.coremc.core.spawner;

import java.util.UUID;

/**
 * One placed, registered CoreMC spawner. Identity is the block
 * position (one registration per block); stacked spawners share one
 * block and one entry with a count.
 *
 * @param world    world name
 * @param x        block x
 * @param y        block y
 * @param z        block z
 * @param mobId    mob config id (e.g. {@code pig})
 * @param variant  current variant
 * @param islandId the island the spawner belongs to (luck + credit)
 * @param amount   how many spawners are stacked on this block (1 = single)
 */
public record SpawnerEntry(String world, int x, int y, int z, String mobId,
                           SpawnerVariant variant, UUID islandId, int amount) {

    /** Single-spawner constructor (stack count 1). */
    public SpawnerEntry(final String world, final int x, final int y, final int z,
                        final String mobId, final SpawnerVariant variant, final UUID islandId) {
        this(world, x, y, z, mobId, variant, islandId, 1);
    }

    public SpawnerEntry {
        amount = Math.max(1, amount);
    }

    /** Stable map key for the block position. */
    public String key() {
        return world + ":" + x + ":" + y + ":" + z;
    }

    /** The same entry with a different stack count. */
    public SpawnerEntry withAmount(final int newAmount) {
        return new SpawnerEntry(world, x, y, z, mobId, variant, islandId, newAmount);
    }
}
