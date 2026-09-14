package com.coremc.core.world;

import java.util.Collections;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

/**
 * The generator behind CoreMC's dedicated island world.
 *
 * Every chunk is empty void — islands are pasted block-by-block by the
 * island service at grid positions, never by terrain. This keeps the
 * world tiny on disk and guarantees no vanilla terrain leaks between
 * islands.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    @Override
    public List<BlockPopulator> getDefaultPopulators(final World world) {
        return Collections.emptyList();
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public Location getFixedSpawnLocation(final World world, final java.util.Random random) {
        return new Location(world, 0.5, 65, 0.5, 0f, 0f);
    }
}
