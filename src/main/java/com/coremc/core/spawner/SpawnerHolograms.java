package com.coremc.core.spawner;

import com.coremc.core.util.ColorUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The floating stack label above every placed spawner: a persistent
 * TextDisplay entity — {@code 32x Pig Spawner [Normal]} — created
 * with the spawner, refreshed whenever the stack changes and removed
 * with it. Chunks carry their holograms: on chunk load, stale labels
 * whose spawner is gone are cleaned up and missing ones created.
 */
public final class SpawnerHolograms implements Listener {

    private final SpawnerService spawners;
    private final NamespacedKey holoKey;

    public SpawnerHolograms(final JavaPlugin plugin, final SpawnerService spawners) {
        this.spawners = spawners;
        this.holoKey = new NamespacedKey(plugin, "coremc_hologram");
    }

    /** Creates or refreshes the label of one placed spawner. */
    public void ensure(final SpawnerEntry entry) {
        final World world = Bukkit.getWorld(entry.world());
        if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
            return;
        }
        final SpawnerMob mob = spawners.mobById(entry.mobId());
        if (mob == null) {
            return;
        }
        final Location above = new Location(world,
                entry.x() + 0.5, entry.y() + 1.4, entry.z() + 0.5);
        TextDisplay display = find(world, entry);
        if (display == null) {
            display = world.spawn(above, TextDisplay.class, created -> {
                created.setPersistent(true);
                created.setBillboard(Display.Billboard.CENTER);
                created.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING,
                        entry.key());
            });
        }
        display.text(LegacyComponentSerializer.legacySection().deserialize(
                ColorUtil.colorize(SpawnerStacks.stackName(mob.name(), entry.variant(),
                        entry.amount()))));
    }

    /** Removes the label of a spawner (broken / stacked to zero / island deleted). */
    public void remove(final SpawnerEntry entry) {
        final World world = Bukkit.getWorld(entry.world());
        if (world == null) {
            return;
        }
        final TextDisplay display = find(world, entry);
        if (display != null) {
            display.remove();
        }
    }

    private TextDisplay find(final World world, final SpawnerEntry entry) {
        final Location centre = new Location(world,
                entry.x() + 0.5, entry.y() + 1.0, entry.z() + 0.5);
        for (final Entity entity : world.getNearbyEntities(centre, 0.6, 1.2, 0.6)) {
            if (entity instanceof TextDisplay display
                    && entry.key().equals(entity.getPersistentDataContainer()
                            .get(holoKey, PersistentDataType.STRING))) {
                return display;
            }
        }
        return null;
    }

    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        final Chunk chunk = event.getChunk();
        for (final Entity entity : chunk.getEntities()) {
            if (entity instanceof TextDisplay display) {
                final String key = display.getPersistentDataContainer()
                        .get(holoKey, PersistentDataType.STRING);
                if (key != null && spawners.entryByKey(key) == null) {
                    display.remove(); // orphaned label — its spawner is gone
                }
            }
        }
        for (final SpawnerEntry entry : spawners.entriesIn(event.getWorld().getName(),
                chunk.getX(), chunk.getZ())) {
            ensure(entry);
        }
    }
}
