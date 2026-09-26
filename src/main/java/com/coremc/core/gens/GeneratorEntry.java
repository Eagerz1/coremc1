package com.coremc.core.gens;

import java.util.UUID;

/**
 * One placed, registered CoreMC generator. Identity is the block
 * position (one registration per block); stacked generators share a
 * block and one entry with a count — the same shape the spawner
 * system uses, so both systems read and persist alike.
 *
 * @param world    world name
 * @param x        block x
 * @param y        block y
 * @param z        block z
 * @param genId    generator config id (e.g. {@code iron})
 * @param islandId the island the generator stands on
 * @param owner    the player who placed it (earns its output)
 * @param amount   how many generators are stacked here (1 = single)
 */
public record GeneratorEntry(String world, int x, int y, int z, String genId,
                             UUID islandId, UUID owner, int amount) {

    /** Single-generator constructor (stack count 1). */
    public GeneratorEntry(final String world, final int x, final int y, final int z,
                          final String genId, final UUID islandId, final UUID owner) {
        this(world, x, y, z, genId, islandId, owner, 1);
    }

    public GeneratorEntry {
        amount = Math.max(1, amount);
    }

    /** Stable map key for the block position. */
    public String key() {
        return world + ":" + x + ":" + y + ":" + z;
    }

    /** The same entry with a different stack count. */
    public GeneratorEntry withAmount(final int newAmount) {
        return new GeneratorEntry(world, x, y, z, genId, islandId, owner, newAmount);
    }

    /** The same entry upgraded to another generator type. */
    public GeneratorEntry withGenerator(final String newGenId) {
        return new GeneratorEntry(world, x, y, z, newGenId, islandId, owner, amount);
    }

    /** Chunk x of the block. */
    public int chunkX() {
        return x >> 4;
    }

    /** Chunk z of the block. */
    public int chunkZ() {
        return z >> 4;
    }
}
