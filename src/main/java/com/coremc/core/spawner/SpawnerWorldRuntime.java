package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.placeable.PlaceableService;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.spawner.SpawnerEntry;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;

/** Owns live spawner tile configuration, snapshot caching, and stalled-tile recovery. */
final class SpawnerWorldRuntime {

    private static final int SPAWN_RANGE_BLOCKS = 1;
    private static final int REROLL_DELAY_TICKS = 10;

    private final CoreMCPlugin plugin;
    private final SpawnerService catalogue;
    private final Map<EntityType, EntitySnapshot> snapshots = new EnumMap<>(EntityType.class);
    private final Map<String, Integer> stalled = new HashMap<>();
    private org.bukkit.scheduler.BukkitTask watchdog;

    SpawnerWorldRuntime(final CoreMCPlugin plugin, final SpawnerService catalogue) {
        this.plugin = plugin;
        this.catalogue = catalogue;
    }

    synchronized EntitySnapshot snapshotFor(final EntityType type) {
        EntitySnapshot snapshot = snapshots.get(type);
        if (snapshot == null) {
            final String nbt = "{id:\"" + type.getKey() + "\"}";
            snapshot = Bukkit.getEntityFactory().createEntitySnapshot(nbt);
            snapshots.put(type, snapshot);
        }
        return snapshot;
    }

    void configureWorldSpawner(final Block block, final SpawnerService.TierRef ref, final int delayTicks) {
        if (!(block.getState() instanceof CreatureSpawner spawner)) {
            return;
        }
        applyToState(spawner, ref, delayTicks, delayTicks);
        spawner.update(true);
    }

    private void applyToState(final CreatureSpawner spawner, final SpawnerService.TierRef ref,
            final int delayTicks, final int firstDelayTicks) {
        final EntitySnapshot snapshot = snapshotFor(ref.mob().entityType());
        final SpawnerEntry entry = new SpawnerEntry(snapshot, 1, null);
        // NB: never call setSpawnedType() here — it resets SpawnPotentials to
        // an empty WeightedList, which makes Paper stall the tile at Delay:0.
        spawner.setSpawnedEntity(entry);
        spawner.setPotentialSpawns(List.of(entry));
        spawner.setSpawnCount(Math.max(1, ref.tier().spawnCount()));
        final int delay = Math.max(20, delayTicks);
        spawner.setMinSpawnDelay(delay);
        spawner.setMaxSpawnDelay(delay);
        spawner.setSpawnRange(SPAWN_RANGE_BLOCKS);
        spawner.setDelay(Math.max(1, Math.min(firstDelayTicks, delay)));
    }

    void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdog = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWatchdog, 100L, 40L);
    }

    void stopWatchdog() {
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
    }

    private void tickWatchdog() {
        for (final Map.Entry<String, PlaceableService.Placement> entry
                : plugin.placeables().placements().entrySet()) {
            final PlaceableService.Placement placement = entry.getValue();
            if (placement.type() != PlaceableService.Type.SPAWNER) {
                continue;
            }
            final String key = entry.getKey();
            final World world = Bukkit.getWorld(placement.worldName());
            if (world == null || !world.isChunkLoaded(placement.x() >> 4, placement.z() >> 4)) {
                stalled.remove(key);
                continue;
            }
            final Block block = world.getBlockAt(placement.x(), placement.y(), placement.z());
            if (!(block.getState() instanceof CreatureSpawner spawner)) {
                stalled.remove(key);
                continue;
            }
            final SpawnerService.TierRef ref = catalogue.tierFor(placement.id()).orElse(null);
            final boolean needsPotentials = spawner.getPotentialSpawns().isEmpty();
            if (ref != null && (needsPotentials || spawner.getSpawnCount() <= 0)) {
                applyToState(spawner, ref, ref.tier().spawnDelayTicks(), ref.tier().spawnDelayTicks());
                spawner.update(true);
                stalled.remove(key);
                plugin.getLogger().fine("Normalised spawner at " + key + " (" + placement.id() + ")");
                continue;
            }
            final int range = Math.max(1, spawner.getRequiredPlayerRange());
            final boolean playerInRange = world.getPlayers().stream().anyMatch(player ->
                    player.getWorld() == world && player.getLocation().distanceSquared(
                            block.getLocation().add(0.5, 0.5, 0.5)) <= (double) range * range);
            if (spawner.getDelay() <= 0 && playerInRange) {
                final int strikes = stalled.merge(key, 1, Integer::sum);
                if (strikes >= 2 && ref != null) {
                    applyToState(spawner, ref, ref.tier().spawnDelayTicks(), REROLL_DELAY_TICKS);
                    spawner.update(true);
                    stalled.remove(key);
                    plugin.getLogger().fine("Re-armed stalled spawner at " + key
                            + " (" + placement.id() + ")");
                }
            } else {
                stalled.remove(key);
            }
        }
    }
}
