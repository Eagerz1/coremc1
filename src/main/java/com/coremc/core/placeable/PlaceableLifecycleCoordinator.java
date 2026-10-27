package com.coremc.core.placeable;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.gen.GeneratorLifecycleCoordinator;
import com.coremc.core.spawner.SpawnerLifecycleCoordinator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * World rules for core placeables:
 *  - placed PDC-tagged blocks join the placement registry (HIGHEST, only if
 *    nothing cancelled the placement),
 *  - breaking a registered block suppresses vanilla drops and yields exactly
 *    one fresh core item after the event completes (dupe-safe: an item
 *    placed twice is registered twice, consumed per-placement),
 *  - explosions disarm placed blocks (registry cleared; item destroyed),
 *  - right-clicking a placed generator harvests its product with a
 *    per-block cooldown so farms tick without redstone exploits.
 *
 * Buff pipeline: spawner delays multiply with the island spawner-boost
 * at place time, harvest amounts scale with the generator-boost, and
 * the rare-product chance rolls with island-luck — each exactly once.
 */
public final class PlaceableLifecycleCoordinator {

    private final CoreMCPlugin plugin;
    private final PlaceableService placeables;
    private final GeneratorLifecycleCoordinator generators;
    private final SpawnerLifecycleCoordinator spawners;

    /** Breaks removed from storage before final event cancellation is known. */
    private final Map<BlockBreakEvent, PendingBreak> pendingBreaks = new IdentityHashMap<>();
    private final Map<PendingBreak, BlockBreakEvent> scheduledResolutions = new IdentityHashMap<>();

    private record PendingBreak(PlaceableService.Placement placement, Location location, Material originalType) {}

    public PlaceableLifecycleCoordinator(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.placeables = plugin.placeables();
        this.generators = plugin.generators().lifecycle();
        this.spawners = plugin.spawners().lifecycle();
    }
    public void onPlace(final BlockPlaceEvent event) {
        final Optional<Map.Entry<PlaceableService.Type, String>> id =
                placeables.idOf(event.getItemInHand());
        if (id.isEmpty()) {
            return;
        }
        final Block block = event.getBlockPlaced();
        if (!placeables.register(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                id.get().getKey(), id.get().getValue(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            plugin.messages().sendPrefixed(event.getPlayer(), "placeable.save-failed", Map.of());
            return;
        }
        if (id.get().getKey() == PlaceableService.Type.SPAWNER) {
            spawners.configurePlacedSpawner(block, id.get().getValue(), event.getPlayer());
        }
    }
    public void onBreak(final BlockBreakEvent event) {
        final Optional<PlaceableService.Placement> placement = placeables.at(event.getBlock().getLocation());
        if (placement.isEmpty()) {
            return;
        }
        final UUID placer = placement.get().owner();
        if (placer != null && !placer.equals(event.getPlayer().getUniqueId())
                && !event.getPlayer().hasPermission("coremc.island.bypass")) {
            event.setCancelled(true);
            plugin.messages().sendPrefixed(event.getPlayer(), "gen.not-yours", Map.of());
            return;
        }
        event.setDropItems(false);
        if (!placeables.unregister(event.getBlock().getLocation())) {
            event.setCancelled(true);
            plugin.messages().sendPrefixed(event.getPlayer(), "placeable.save-failed", Map.of());
            return;
        }
        pendingBreaks.put(event, new PendingBreak(placement.get(), event.getBlock().getLocation().clone(), event.getBlock().getType()));
    }

    /** Complete a core-block break after the event has finished dispatching. */
    public void onBreakMonitor(final BlockBreakEvent event) {
        final PendingBreak pending = pendingBreaks.remove(event);
        if (pending == null) {
            return;
        }
        scheduledResolutions.put(pending, event);
        plugin.tasks().runLater(() -> {
            scheduledResolutions.remove(pending);
            resolveBreak(pending);
        }, 1L);
    }

    /** Resolve queued break outcomes before the plugin cancels scheduled tasks on shutdown. */
    public void resolvePendingBreaksOnShutdown() {
        for (final Map.Entry<PendingBreak, BlockBreakEvent> entry : List.copyOf(scheduledResolutions.entrySet())) {
            resolveBreak(entry.getKey());
        }
        scheduledResolutions.clear();
    }

    private void resolveBreak(final PendingBreak pending) {
        final Block block = pending.location().getBlock();
        if (block.getType() == pending.originalType()) {
            restorePlacement(pending);
            return;
        }
        final PlaceableService.Placement placement = pending.placement();
        final ItemStack returned = switch (placement.type()) {
            case GENERATOR -> generators.mintForBreak(placement.id()).orElse(null);
            case SPAWNER -> spawners.mint(placement.id()).orElse(null);
        };
        if (returned != null) {
            pending.location().getWorld().dropItemNaturally(pending.location(), returned);
        }
    }

    private void restorePlacement(final PendingBreak pending) {
        final PlaceableService.Placement placement = pending.placement();
        if (!placeables.restoreAfterCancelledBreak(pending.location().getWorld().getName(), placement)) {
            plugin.getLogger().severe("Could not restore the placeable registry after a cancelled block break at "
                    + pending.location() + "; keeping the placement in memory and retrying on shutdown.");
        }
    }
    public void onPistonExtend(final org.bukkit.event.block.BlockPistonExtendEvent event) {
        for (final Block block : event.getBlocks()) {
            if (placeables.at(block.getLocation()).isPresent()) {
                event.setCancelled(true); // a pushed block would desync from the placement registry
                return;
            }
        }
    }
    public void onPistonRetract(final org.bukkit.event.block.BlockPistonRetractEvent event) {
        for (final Block block : event.getBlocks()) {
            if (placeables.at(block.getLocation()).isPresent()) {
                event.setCancelled(true);
                return;
            }
        }
    }
    public void onEntityExplode(final EntityExplodeEvent event) {
        if (!clearAffected(event.blockList())) {
            event.setCancelled(true);
        }
    }
    public void onBlockExplode(final BlockExplodeEvent event) {
        if (!clearAffected(event.blockList())) {
            event.setCancelled(true);
        }
    }

    private boolean clearAffected(final List<Block> blocks) {
        return placeables.unregisterAll(blocks.stream().map(Block::getLocation).toList());
    }

    // ------------------------------------------------------------------ generator harvest
    public void onInteract(final PlayerInteractEvent event) {
        generators.onInteract(event);
    }
}
