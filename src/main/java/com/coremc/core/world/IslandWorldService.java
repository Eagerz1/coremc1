package com.coremc.core.world;

import com.coremc.core.config.CoreConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

/**
 * Creates (or loads) CoreMC's dedicated void island world.
 *
 * The world is created by the plugin on first boot with the void
 * generator; on later boots the folder already exists and is simply
 * loaded with the same generator, so previously generated chunks stay
 * void and islands persist across restarts.
 */
public final class IslandWorldService {

    private final CoreConfig config;
    private World islandWorld;

    public IslandWorldService(final CoreConfig config) {
        this.config = config;
    }

    /** The island world, creating it on first call. Never returns null. */
    public synchronized World islandWorld() {
        if (islandWorld != null) {
            return islandWorld;
        }
        final String name = config.islandWorldName();
        final World existing = Bukkit.getWorld(name);
        if (existing != null) {
            this.islandWorld = existing;
            return existing;
        }
        final World created = WorldCreator.name(name)
                .environment(World.Environment.NORMAL)
                .generator(new VoidChunkGenerator())
                .generateStructures(false)
                .seed(0L)
                .createWorld();
        if (created == null) {
            throw new IllegalStateException("Failed to create island world '" + name + "'");
        }
        this.islandWorld = created;
        return created;
    }
}
