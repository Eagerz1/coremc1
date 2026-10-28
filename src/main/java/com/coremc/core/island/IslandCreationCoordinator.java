package com.coremc.core.island;

import com.coremc.core.config.CoreConfig;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Coordinates island reservations, starter-world generation, and durable publication. */
public final class IslandCreationCoordinator {

    private final CoreConfig config;
    private final IslandPersistence persistence;
    private final IslandPlatformBuilder platformBuilder;
    private final Logger logger;
    private final IslandCreationHost host;
    private final Set<UUID> pendingOwners = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingCells = ConcurrentHashMap.newKeySet();
    private final Map<String, Island> pendingIslandsByCell = new ConcurrentHashMap<>();

    public IslandCreationCoordinator(
            final CoreConfig config,
            final IslandPersistence persistence,
            final IslandPlatformBuilder platformBuilder,
            final Logger logger,
            final IslandCreationHost host) {
        this.config = config;
        this.persistence = persistence;
        this.platformBuilder = platformBuilder;
        this.logger = logger;
        this.host = host;
    }

    public IslandService.CreateResult createIsland(
            final Player player,
            final IslandTheme theme,
            final Consumer<Island> onCreated,
            final Consumer<Throwable> onFailure) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Island creation must run on the main thread");
        }
        if (!host.registryReady()) {
            return IslandService.CreateResult.CREATION_PENDING;
        }

        final UUID owner = player.getUniqueId();
        if (host.hasPendingMembershipWrite(owner)) {
            return IslandService.CreateResult.CREATION_PENDING;
        }
        if (host.hasIsland(owner)) {
            return IslandService.CreateResult.ALREADY_ISLAND;
        }
        if (!pendingOwners.add(owner)) {
            return IslandService.CreateResult.CREATION_PENDING;
        }

        final var worldOptional = host.islandWorld();
        if (worldOptional.isEmpty()) {
            pendingOwners.remove(owner);
            return IslandService.CreateResult.WORLD_MISSING;
        }

        final World target = worldOptional.get();
        final int spacing = config.islandSpacing();
        final Set<String> occupied = occupiedCellsIncludingReservations();
        final int[] cell = GridAssigner.nextFreeCell(occupied, target.getName());
        final String cellKey = GridAssigner.key(cell[0], cell[1], target.getName());
        pendingCells.add(cellKey);

        final Island island = new Island(
                UUID.randomUUID(), owner, target.getName(), cell[0] * spacing, config.islandStartHeight(),
                cell[1] * spacing, config.islandBorderSize(), System.currentTimeMillis());
        island.theme(theme == null ? "plains" : theme.key());
        island.creationPending(true);
        pendingIslandsByCell.put(cellKey, island);

        try {
            persistence.execute(() -> {
                try {
                    persistence.save(island);
                } catch (IOException exception) {
                    release(owner, cellKey, island);
                    logger.log(Level.SEVERE, "Failed to persist island creation marker for " + island.islandId(), exception);
                    host.scheduleCreationCallback(() -> onFailure.accept(exception), "report island creation failure");
                    return;
                }

                host.scheduleCreationCallback(() -> {
                    final World creationWorld = Bukkit.getWorld(island.worldName());
                    if (creationWorld == null) {
                        onFailure.accept(new IllegalStateException(
                                "Island world '" + island.worldName() + "' is not loaded"));
                        return;
                    }
                    try {
                        platformBuilder.generate(creationWorld, island, theme);
                    } catch (RuntimeException exception) {
                        logger.log(Level.SEVERE, "Failed to generate starter island for " + owner, exception);
                        onFailure.accept(exception);
                        return;
                    }

                    island.creationPending(false);
                    try {
                        persistence.execute(() -> {
                            try {
                                persistence.save(island);
                            } catch (IOException exception) {
                                island.creationPending(true);
                                logger.log(Level.SEVERE,
                                        "Failed to commit newly created island " + island.islandId(), exception);
                                host.scheduleCreationCallback(
                                        () -> onFailure.accept(exception), "report island commit failure");
                                return;
                            }

                            host.scheduleCreationCallback(() -> {
                                host.registerCreated(island);
                                release(owner, cellKey, island);
                                host.associateOwner(island);
                                onCreated.accept(island);
                            }, "publish newly created island");
                        });
                    } catch (RejectedExecutionException exception) {
                        island.creationPending(true);
                        logger.log(Level.SEVERE,
                                "Island storage is shutting down; could not commit " + island.islandId(), exception);
                        onFailure.accept(exception);
                    }
                }, "generate starter island");
            });
        } catch (RejectedExecutionException exception) {
            release(owner, cellKey, island);
            logger.log(Level.SEVERE,
                    "Island storage is shutting down; could not persist " + island.islandId(), exception);
            return IslandService.CreateResult.CREATION_FAILED;
        }

        return IslandService.CreateResult.CREATED;
    }

    public boolean isPending(final Island island) {
        if (island == null) {
            return false;
        }
        final int[] cell = island.gridCell(config.islandSpacing());
        final String cellKey = GridAssigner.key(cell[0], cell[1], island.worldName());
        return pendingIslandsByCell.get(cellKey) == island;
    }

    public Island pendingIslandAt(final String worldName, final int x, final int z) {
        final int spacing = config.islandSpacing();
        for (final int[] cell : IslandService.candidateCells(x, z, spacing)) {
            final Island pending = pendingIslandsByCell.get(GridAssigner.key(cell[0], cell[1], worldName));
            if (pending != null && pending.containsBlock(x, z)) {
                return pending;
            }
        }
        return null;
    }

    private Set<String> occupiedCellsIncludingReservations() {
        final Set<String> occupied = new HashSet<>(host.occupiedCells());
        occupied.addAll(pendingCells);
        return occupied;
    }

    public void clearTransient() {
        pendingOwners.clear();
        pendingCells.clear();
        pendingIslandsByCell.clear();
    }

    private void release(final UUID owner, final String cellKey, final Island island) {
        pendingOwners.remove(owner);
        pendingCells.remove(cellKey);
        pendingIslandsByCell.remove(cellKey, island);
    }
}
