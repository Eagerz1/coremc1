package com.coremc.core.island;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * The generator behind CoreMC's dedicated island world.
 *
 * Every chunk is empty void (biomes default to plains) — islands are
 * built block-by-block by {@link IslandService} at grid positions, never
 * by terrain. This keeps the world tiny on disk, protects the main
 * world from island clutter and guarantees no ocean/vanilla terrain
 * leaks between islands (the "dedicated island world" requirement).
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    @Override
    public void generateNoise(
            final WorldInfo worldInfo,
            final Random random,
            final int chunkX,
            final int chunkZ,
            final ChunkData chunkData) {
        // Void: set the base biome so the world is not "minecraft:void" everywhere,
        // which would tint grass/sky grey on clients.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunkData.setBiome(x, 0, z, Biome.PLAINS);
            }
        }
    }

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
    public Location getFixedSpawnLocation(final World world, final Random random) {
        return new Location(world, 0.5, 65, 0, 0f, 0f);
    }

    /** No-op nicety kept for tests: the void generator never fills blocks. */
    static boolean alwaysAir(final Material material) {
        return material == Material.AIR;
    }
}
