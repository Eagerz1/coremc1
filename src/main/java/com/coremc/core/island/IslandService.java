package com.coremc.core.island;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.scheduler.TaskService;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns islands: registry, grid assignment, starter-island generation,
 * persistence and lifecycle.
 *
 * The full registry is loaded once at startup (async), then mutated on
 * the main thread. Disk I/O runs on a dedicated daemon executor; the
 * registry map is the authoritative source while the server runs.
 */
public final class IslandService {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final CoreConfig config;
    private final IslandDataStore store;
    private final TaskService tasks;

    private final Map<UUID, Island> islandsByOwner = new ConcurrentHashMap<>();
    private final Set<String> usedCells = ConcurrentHashMap.newKeySet();
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "CoreMC-Islands");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean loaded = false;

    public IslandService(
            final JavaPlugin plugin, final CoreConfig config, final IslandDataStore store, final TaskService tasks) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.store = store;
        this.tasks = tasks;
    }

    /** Loads the registry from disk on the I/O executor. */
    public void start() {
        io.execute(() -> {
            try {
                for (final Island island : store.loadAll()) {
                    register(island);
                }
                logger.info("Loaded " + islandsByOwner.size() + " island(s).");
            } catch (final IOException exception) {
                logger.log(Level.SEVERE, "Failed to load islands — island commands may misbehave.", exception);
            } finally {
                loaded = true;
            }
        });
    }

    /** Whether the registry finished loading (commands wait for this). */
    public boolean isLoaded() {
        return loaded;
    }

    public Optional<Island> islandOf(final UUID owner) {
        return Optional.ofNullable(islandsByOwner.get(owner));
    }

    public int islandCount() {
        return islandsByOwner.size();
    }

    /** The configured island world, or empty if it does not exist. */
    public Optional<World> islandWorld() {
        return Optional.ofNullable(Bukkit.getWorld(config.islandWorldName()));
    }

    /**
     * Creates an island for {@code player}: assigns the next grid cell,
     * generates the starter platform and registers + persists the island.
     * Must be called on the main thread. Returns the created island.
     *
     * @throws IllegalStateException if the owner already has an island or
     *         the island world does not exist
     */
    public Island createIsland(final Player player) throws IOException {
        final UUID owner = player.getUniqueId();
        if (islandsByOwner.containsKey(owner)) {
            throw new IllegalStateException("Owner already has an island");
        }
        final World world = islandWorld()
                .orElseThrow(() -> new IllegalStateException(
                        "Island world '" + config.islandWorldName() + "' does not exist"));

        final int spacing = config.islandSpacing();
        final int[] cell = GridAssigner.nextFreeCell(usedCells, world.getName());
        final int centerX = cell[0] * spacing;
        final int centerZ = cell[1] * spacing;
        final int centerY = config.islandStartHeight();

        final Island island = new Island(
                UUID.randomUUID(), owner, world.getName(), centerX, centerY, centerZ, System.currentTimeMillis());

        generateStarterIsland(world, island);
        register(island);

        final Island toSave = island;
        io.execute(() -> {
            try {
                store.save(toSave);
            } catch (final IOException exception) {
                logger.log(Level.SEVERE, "Failed to save island " + toSave.islandId(), exception);
            }
        });
        return island;
    }

    /** Deletes an island (data + registry + grid cell). Platform blocks stay in the world. */
    public void deleteIsland(final UUID owner) throws IOException {
        final Island removed = islandsByOwner.remove(owner);
        if (removed == null) {
            throw new IllegalStateException("Owner has no island");
        }
        final int spacing = config.islandSpacing();
        final int[] cell = removed.gridCell(spacing);
        usedCells.remove(GridAssigner.key(cell[0], cell[1], removed.worldName()));

        io.execute(() -> {
            try {
                store.delete(owner);
            } catch (final IOException exception) {
                logger.log(Level.SEVERE, "Failed to delete island file of " + owner, exception);
            }
        });
    }

    /** Teleports the player to their island home synchronously (main thread). */
    public void teleportHome(final Player player, final Island island) {
        final World world = Bukkit.getWorld(island.worldName());
        if (world == null) {
            throw new IllegalStateException("Island world '" + island.worldName() + " is not loaded");
        }
        final Location home = new Location(world, island.homeX(), island.homeY(), island.homeZ());
        player.teleport(home);
    }

    /** Flushes nothing (islands save eagerly) and stops the I/O executor. Called on disable. */
    public void shutdown() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Island executor did not terminate in 10s.");
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while stopping island service", exception);
        }
        islandsByOwner.clear();
        usedCells.clear();
    }

    private void register(final Island island) {
        islandsByOwner.put(island.owner(), island);
        final int spacing = config.islandSpacing();
        final int[] cell = island.gridCell(spacing);
        usedCells.add(GridAssigner.key(cell[0], cell[1], island.worldName()));
    }

    /**
     * Builds the classic starter island: a 5x5 grass platform on dirt,
     * one bedrock in the centre and an oak tree in the north-east corner.
     */
    private void generateStarterIsland(final World world, final Island island) {
        final int cx = island.centerX();
        final int cy = island.centerY();
        final int cz = island.centerZ();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                setBlock(world, cx + dx, cy - 1, cz + dz, Material.DIRT);
                setBlock(world, cx + dx, cy, cz + dz, Material.GRASS_BLOCK);
            }
        }
        setBlock(world, cx, cy - 1, cz, Material.BEDROCK);

        final Location sapling = new Location(world, cx + 2, cy + 1, cz + 2);
        setBlock(world, cx + 2, cy + 1, cz + 2, Material.OAK_SAPLING);
        if (!world.generateTree(sapling, TreeType.TREE)) {
            logger.fine("Tree generation reported failure at " + sapling + " — sapling remains.");
        }
    }

    private void setBlock(final World world, final int x, final int y, final int z, final Material material) {
        final Block block = world.getBlockAt(x, y, z);
        block.setType(material);
    }
}
