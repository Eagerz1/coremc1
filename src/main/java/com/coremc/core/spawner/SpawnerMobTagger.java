package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Adds CoreMC identity and presentation to mobs from registered spawners.
 * Also upgrades tagged mobs already saved in loaded chunks after a plugin
 * update, and when an old entity is loaded from disk.
 */
public final class SpawnerMobTagger implements Listener {

    private final CoreMCPlugin plugin;
    private final SpawnerTags tags;

    public SpawnerMobTagger(final CoreMCPlugin plugin, final SpawnerTags tags) {
        this.plugin = plugin;
        this.tags = tags;
        // The server's initial chunks are loaded before plugin listeners.
        Bukkit.getScheduler().runTask(plugin, this::refreshLoadedEntities);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        // Find the source CoreMC spawner block within vanilla spawn range.
        final var sourceBlock =
                tags.findSourceBlock(plugin, event.getLocation(), event.getEntityType());
        if (sourceBlock.isEmpty()) {
            return;
        }
        final var placement = plugin.placeables().at(sourceBlock.get().getLocation());
        if (placement.isEmpty()) {
            return;
        }
        final String tierId = placement.get().id();
        if (plugin.spawners().tierFor(tierId).isEmpty()) {
            return;
        }
        tags.tag(event.getEntity(), tierId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(final ChunkLoadEvent event) {
        for (final Entity entity : event.getChunk().getEntities()) {
            refresh(entity);
        }
    }

    private void refreshLoadedEntities() {
        for (final World world : Bukkit.getWorlds()) {
            for (final Entity entity : world.getLivingEntities()) {
                refresh(entity);
            }
        }
    }

    private void refresh(final Entity entity) {
        if (!tags.isSpawnerBorn(entity)) {
            return;
        }
        tags.tierIdOf(entity).ifPresent(tierId -> tags.tag(entity, tierId));
    }

    /** ZOMBIFIED_PIGLIN -> "Zombified Piglin" for player-facing names. */
    public static String prettyEntityName(final String enumName) {
        final String[] parts = enumName.toLowerCase(java.util.Locale.ROOT).split("_");
        final StringBuilder out = new StringBuilder();
        for (final String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
