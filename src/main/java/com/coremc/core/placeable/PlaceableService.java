package com.coremc.core.placeable;

import com.coremc.core.CoreMCPlugin;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

/** Public facade for deployable item identity and durable world placement tracking. */
public final class PlaceableService {

    public enum Type {
        GENERATOR("GEN"),
        SPAWNER("SPW");

        private final String tag;

        Type(final String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }

        static Type byTag(final String tag) {
            for (final Type type : values()) {
                if (type.tag.equals(tag)) {
                    return type;
                }
            }
            return null;
        }
    }

    /** Immutable placement record. */
    public record Placement(String worldName, Type type, String id, UUID owner, int x, int y, int z) {}

    private final PlaceableIdentity identity;
    private final PlaceableRegistryStore registry;

    public PlaceableService(final CoreMCPlugin plugin) {
        this.identity = new PlaceableIdentity(plugin);
        this.registry = new PlaceableRegistryStore(plugin);
    }

    public ItemStack identify(final ItemStack stack, final Type type, final String id) {
        return identity.identify(stack, type, id);
    }

    public Optional<Map.Entry<Type, String>> idOf(final ItemStack stack) {
        return identity.idOf(stack);
    }

    /** Canonical encoded key for a world block location. */
    public static String keyOf(final String world, final int x, final int y, final int z) {
        return PlaceableRegistryStore.keyOf(world, x, y, z);
    }

    public void load() {
        registry.load();
    }

    public boolean save() {
        return registry.save();
    }

    public boolean register(final String worldName, final int x, final int y, final int z,
            final Type type, final String id, final UUID owner) {
        return registry.register(worldName, x, y, z, type, id, owner);
    }

    public boolean restoreAfterCancelledBreak(final String worldName, final Placement placement) {
        return registry.restoreAfterCancelledBreak(worldName, placement);
    }

    public Optional<Placement> at(final Location location) {
        if (location.getWorld() == null) {
            return Optional.empty();
        }
        return registry.at(location);
    }

    public boolean unregister(final Location location) {
        return registry.unregister(location);
    }

    public boolean unregisterAll(final java.util.Collection<Location> locations) {
        return registry.unregisterAll(locations);
    }

    public int size() {
        return registry.size();
    }

    public Map<String, Placement> placements() {
        return registry.placements();
    }
}
