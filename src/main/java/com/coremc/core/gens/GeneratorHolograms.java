package com.coremc.core.gens;

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
 * The floating label above every placed generator — a persistent
 * TextDisplay entity ({@code 8x ɪʀᴏɴ ɢᴇɴᴇʀᴀᴛᴏʀ}) created with the
 * generator, refreshed whenever the stack or tier changes and removed
 * with it. Same pattern (and same chunk-load housekeeping) as the
 * spawner holograms, so both systems behave alike.
 */
public final class GeneratorHolograms implements Listener {

    private final GeneratorService generators;
    private final NamespacedKey holoKey;

    public GeneratorHolograms(final JavaPlugin plugin, final GeneratorService generators) {
        this.generators = generators;
        this.holoKey = new NamespacedKey(plugin, "coremc_gen_hologram");
    }

    /** Creates or refreshes the label of one placed generator. */
    public void ensure(final GeneratorEntry entry) {
        final World world = Bukkit.getWorld(entry.world());
        if (world == null || !world.isChunkLoaded(entry.chunkX(), entry.chunkZ())) {
            return;
        }
        final GeneratorTier tier = generators.config().byId(entry.genId());
        if (tier == null) {
            return;
        }
        final Location above = new Location(world,
                entry.x() + 0.5, entry.y() + 1.2, entry.z() + 0.5);
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
                ColorUtil.colorize(GeneratorLore.hologram(tier, entry.amount()))));
    }

    /** Removes the label of a generator (broken, upgraded away, island deleted). */
    public void remove(final GeneratorEntry entry) {
        final World world = Bukkit.getWorld(entry.world());
        if (world == null) {
            return;
        }
        final TextDisplay display = find(world, entry);
        if (display != null) {
            display.remove();
        }
    }

    private TextDisplay find(final World world, final GeneratorEntry entry) {
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
                if (key != null && generators.entryByKey(key) == null) {
                    display.remove(); // orphaned label — its generator is gone
                }
            }
        }
        for (final GeneratorEntry entry : generators.entriesIn(event.getWorld().getName(),
                chunk.getX(), chunk.getZ())) {
            ensure(entry);
        }
    }
}
