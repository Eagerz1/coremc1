package com.coremc.core.island;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.player.PlayerDataService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Applies island tombstones before removing live registry and player associations. */
public final class IslandDeletionCoordinator {

    private final CoreConfig config;
    private final IslandRegistry registry;
    private final IslandTombstoneStore tombstones;
    private final IslandPersistence persistence;
    private final PlayerDataService playerData;
    private final IslandMembershipCoordinator membership;
    private final Logger logger;
    private final IslandDeletionHost host;

    public IslandDeletionCoordinator(
            final CoreConfig config,
            final IslandRegistry registry,
            final IslandTombstoneStore tombstones,
            final IslandPersistence persistence,
            final PlayerDataService playerData,
            final IslandMembershipCoordinator membership,
            final Logger logger,
            final IslandDeletionHost host) {
        this.config = config;
        this.registry = registry;
        this.tombstones = tombstones;
        this.persistence = persistence;
        this.playerData = playerData;
        this.membership = membership;
        this.logger = logger;
        this.host = host;
    }

    public void deleteIsland(final UUID owner) throws IOException {
        if (host.purchaseInProgress()) {
            throw new IOException("An island purchase is still being saved; try deleting the island again shortly.");
        }
        final Island removed = registry.ownedIsland(owner)
                .orElseThrow(() -> new IllegalStateException("Owner has no island"));
        final int spacing = config.islandSpacing();
        final int[] cell = removed.gridCell(spacing);
        final String cellKey = GridAssigner.key(cell[0], cell[1], removed.worldName());

        tombstones.retire(cellKey, removed.islandId());
        if (!registry.remove(removed, spacing)) {
            throw new IllegalStateException("Island changed while deletion was being committed");
        }

        membership.purgeIsland(removed.islandId());
        host.purgeActivity(removed.islandId());
        host.clearDirty(removed.islandId());

        final List<UUID> scrubTargets = new ArrayList<>();
        scrubTargets.add(owner);
        scrubTargets.addAll(removed.members());
        final Player ownerPlayer = Bukkit.getPlayer(owner);
        if (ownerPlayer != null) host.rescue(ownerPlayer, removed);
        for (final UUID member : removed.members()) {
            final Player online = Bukkit.getPlayer(member);
            if (online != null) {
                host.rescue(online, removed);
                host.notifyDeletedMember(online);
            }
        }

        try {
            persistence.execute(() -> {
                for (final UUID player : scrubTargets) {
                    playerData.clearIslandAssociationIfMatches(player, removed.islandId());
                }
                try {
                    persistence.delete(owner);
                    logger.info("Deleted island " + removed.islandId() + " of " + owner + " ("
                            + scrubTargets.size() + " association(s) scrubbed, cell retired).");
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Failed to remove tombstoned island file of " + owner, exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            logger.log(Level.WARNING, "Island cleanup queued after shutdown; tombstone remains durable.", exception);
        }
    }
}
