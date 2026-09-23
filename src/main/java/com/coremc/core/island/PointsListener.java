package com.coremc.core.island;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Actions earn island points: every block broken or placed on an
 * island by one of its own members counts, wherever in the island
 * claim it happens. Visitors building on someone else's island earn
 * that island nothing.
 */
public final class PointsListener implements Listener {

    private final IslandService islands;
    private final IslandPointsService points;

    public PointsListener(final IslandService islands, final IslandPointsService points) {
        this.islands = islands;
        this.points = points;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        onAction(event.getBlock(), event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        onAction(event.getBlock(), event.getPlayer());
    }

    private void onAction(final Block block, final org.bukkit.entity.Player player) {
        final Island island = islands.islandAt(block.getWorld(),
                block.getX(), block.getZ());
        if (island == null) {
            return;
        }
        if (!island.isOwner(player.getUniqueId()) && !island.isMember(player.getUniqueId())) {
            return;
        }
        points.add(island, IslandPointsService.ACTION_POINTS);
    }
}
