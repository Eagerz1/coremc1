package com.coremc.core.island;

import java.util.UUID;
import org.bukkit.entity.Player;

/** Runtime effects required after a durable island deletion begins. */
interface IslandDeletionHost {
    boolean purchaseInProgress();
    void purgeActivity(UUID islandId);
    void clearDirty(UUID islandId);
    void rescue(Player player, Island island);
    void notifyDeletedMember(Player player);
}
