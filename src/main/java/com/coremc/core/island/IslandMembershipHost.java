package com.coremc.core.island;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Island policy and side effects required by the membership coordinator. */
interface IslandMembershipHost {
    Optional<Island> ownedIsland(UUID owner);
    int memberCapacity(Island island);
    void setAssociation(UUID player, UUID islandId);
    boolean isCurrentIsland(Island island);
    void rescueIfStranded(Player player, Island island);
    void scheduleMembershipCallback(Runnable callback, String operation);
    void markDirty(Island island);
}
