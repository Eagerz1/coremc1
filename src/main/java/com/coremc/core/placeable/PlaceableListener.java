package com.coremc.core.placeable;

import com.coremc.core.CoreMCPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/** Bukkit event adapter for the deployable lifecycle coordinator. */
public final class PlaceableListener implements Listener {

    private final PlaceableLifecycleCoordinator lifecycle;

    public PlaceableListener(final CoreMCPlugin plugin) {
        this.lifecycle = new PlaceableLifecycleCoordinator(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        lifecycle.onPlace(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        lifecycle.onBreak(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakMonitor(final BlockBreakEvent event) {
        lifecycle.onBreakMonitor(event);
    }

    public void resolvePendingBreaksOnShutdown() {
        lifecycle.resolvePendingBreaksOnShutdown();
    }

    public void resolvePendingExplosionsOnShutdown() {
        lifecycle.resolvePendingExplosionsOnShutdown();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(final org.bukkit.event.block.BlockPistonExtendEvent event) {
        lifecycle.onPistonExtend(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(final org.bukkit.event.block.BlockPistonRetractEvent event) {
        lifecycle.onPistonRetract(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        lifecycle.onEntityExplode(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        lifecycle.onBlockExplode(event);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(final PlayerInteractEvent event) {
        lifecycle.onInteract(event);
    }
}
