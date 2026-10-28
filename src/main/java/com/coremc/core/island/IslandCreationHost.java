package com.coremc.core.island;

import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import org.bukkit.World;

/** Island registry operations needed by the creation coordinator. */
interface IslandCreationHost {

    boolean registryReady();

    boolean hasIsland(UUID owner);

    boolean hasPendingMembershipWrite(UUID owner);

    Optional<World> islandWorld();

    Set<String> occupiedCells();

    void registerCreated(Island island);

    void associateOwner(Island island);

    void scheduleCreationCallback(Runnable callback, String operation);
}
