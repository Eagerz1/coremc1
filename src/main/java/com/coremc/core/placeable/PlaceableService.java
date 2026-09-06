package com.coremc.core.placeable;

import com.coremc.core.CoreMCPlugin;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Identity and world-placement tracking for CoreMC "placeable" items
 * (generators, spawners).
 *
 * Two halves of one concern:
 *  - <b>Identity</b>: every minted item carries {@code coremc:placeable}
 *    in its PersistentDataContainer as {@code "<TYPE>:<id>"}. Identity never
 *    depends on display names or lore, so ordinary items cannot impersonate
 *    a core item and a core item cannot be forged by an anvil rename.
 *  - <b>Placement registry</b>: every placed core block is remembered at
 *    {@code plugins/CoreMC/data/placeables.yml} keyed by
 *    {@code world:x:y:z} with the placing owner. The registry survives
 *    restarts, and breaking a registered block yields exactly one core
 *    item back — vanilla drops are suppressed, so no duplication.
 */
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
    public record Placement(Type type, String id, UUID owner, int x, int y, int z) {}

    private final CoreMCPlugin plugin;
    private final NamespacedKey placeableKey;
    private final File file;
    private final Map<String, Placement> placements = new HashMap<>();

    public PlaceableService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.placeableKey = new NamespacedKey(plugin, "placeable");
        this.file = new File(plugin.getDataFolder(), "data/placeables.yml");
    }

    // ------------------------------------------------------------------ identity

    /** Stamps an item as a core placeable of the given type/id (idempotent). */
    public ItemStack identify(final ItemStack stack, final Type type, final String id) {
        Objects.requireNonNull(stack);
        final ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.getPersistentDataContainer().set(
                placeableKey, PersistentDataType.STRING, type.tag() + ":" + id);
        stack.setItemMeta(meta);
        return stack;
    }

    /** The {@code (type, id)} embedded in an item, or empty for foreign items. */
    public Optional<Map.Entry<Type, String>> idOf(final ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        final String raw = stack.getItemMeta()
                .getPersistentDataContainer()
                .get(placeableKey, PersistentDataType.STRING);
        if (raw == null || raw.length() < 5 || raw.charAt(3) != ':') {
            return Optional.empty();
        }
        final Type type = Type.byTag(raw.substring(0, 3));
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(Map.entry(type, raw.substring(4)));
    }

    // ------------------------------------------------------------------ placement registry

    static String keyOf(final String world, final int x, final int y, final int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public void load() {
        placements.clear();
        if (!file.isFile()) {
            return;
        }
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (final String key : yaml.getKeys(false)) {
            final String[] parts = key.split(":");
            if (parts.length != 4) {
                continue;
            }
            final String typeTag = yaml.getString(key + ".type", "");
            final Type type = Type.byTag(typeTag);
            final String id = yaml.getString(key + ".id", "");
            final UUID owner = uuidOf(yaml.getString(key + ".owner", ""));
            if (type == null || id.isEmpty()) {
                continue;
            }
            try {
                placements.put(
                        key,
                        new Placement(
                                type, id, owner,
                                Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]),
                                Integer.parseInt(parts[3])));
            } catch (NumberFormatException ignored) {
                // drop corrupt row; load stays total
            }
        }
        plugin.getLogger().info("Loaded " + placements.size() + " placed block(s).");
    }

    private static UUID uuidOf(final String raw) {
        try {
            return raw.isEmpty() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Saves all placements (overwrite; small bounded file). */
    public void save() {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<String, Placement> entry : placements.entrySet()) {
            final String key = entry.getKey();
            final Placement p = entry.getValue();
            yaml.set(key + ".type", p.type().tag());
            yaml.set(key + ".id", p.id());
            if (p.owner() != null) {
                yaml.set(key + ".owner", p.owner().toString());
            }
        }
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save placements: " + e.getMessage());
        }
    }

    public void register(final String worldName, final int x, final int y, final int z,
                         final Type type, final String id, final UUID owner) {
        placements.put(keyOf(worldName, x, y, z), new Placement(type, id, owner, x, y, z));
    }

    public Optional<Placement> at(final Location location) {
        if (location.getWorld() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(placements.get(keyOf(
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ())));
    }

    public void unregister(final Location location) {
        if (location.getWorld() == null) {
            return;
        }
        placements.remove(keyOf(
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    public int size() {
        return placements.size();
    }
}
