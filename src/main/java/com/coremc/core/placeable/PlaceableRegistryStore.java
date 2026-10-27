package com.coremc.core.placeable;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.placeable.PlaceableService.Placement;
import com.coremc.core.placeable.PlaceableService.Type;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Durable registry for placed generator and spawner blocks.
 *
 * Every placement is remembered at
 *    {@code plugins/CoreMC/data/placeables.yml} keyed by an encoded,
 *    YAML-safe world-and-coordinate id with the placing owner. The registry survives
 *    restarts, and breaking a registered block yields exactly one core
 *    item back — vanilla drops are suppressed, so no duplication.
 */
final class PlaceableRegistryStore {

    private final CoreMCPlugin plugin;
    private final File file;
    private final Map<String, Placement> placements = new HashMap<>();
    private boolean registryReady;

    PlaceableRegistryStore(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/placeables.yml");
    }
    // ------------------------------------------------------------------ placement registry

    static String keyOf(final String world, final int x, final int y, final int z) {
        final String encodedWorld = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(world.getBytes(StandardCharsets.UTF_8));
        return "v2~" + encodedWorld + "~" + x + "~" + y + "~" + z;
    }

    private record RegistryKey(String worldName, int x, int y, int z) {}

    private static Optional<RegistryKey> parseKey(final String key) {
        if (key.startsWith("v2~")) {
            try {
                final String[] parts = key.split("~", -1);
                if (parts.length == 5) {
                    final String world = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                    if (!world.isBlank()) {
                        return Optional.of(new RegistryKey(world,
                                Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4])));
                    }
                }
            } catch (final IllegalArgumentException ignored) {
                // It may be a legacy world name beginning with "v2~"; try the legacy form below.
            }
        }
        try {
            // Legacy keys used the world's plain name and were vulnerable to YAML path separators.
            final String[] parts = key.split(":", 4);
            if (parts.length != 4 || parts[0].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new RegistryKey(
                    parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
    public void load() {
        registryReady = false;
        placements.clear();
        if (!file.isFile()) {
            registryReady = true;
            return;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (final IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException("Cannot read placeable registry " + file
                    + "; the original file was preserved and CoreMC will fail closed.", exception);
        }
        final boolean hasRegistryVersion = yaml.contains("registry-version");
        if (hasRegistryVersion && yaml.getInt("registry-version", -1) != 1) {
            throw new IllegalStateException("Unsupported placeable registry version in " + file
                    + "; the original file was preserved and CoreMC will fail closed.");
        }
        final Map<String, java.util.Set<String>> rowFields = new HashMap<>();
        for (final String path : yaml.getKeys(true)) {
            if (path.equals("registry-version")) {
                continue;
            }
            if (yaml.get(path) instanceof org.bukkit.configuration.ConfigurationSection) {
                if (parseKey(path).isPresent()) {
                    rowFields.computeIfAbsent(path, ignored -> new java.util.HashSet<>());
                }
                continue;
            }
            final int separator = path.lastIndexOf('.');
            if (separator < 0) {
                throw new IllegalStateException("Unexpected field '" + path + "' in placeable registry " + file);
            }
            final String storedKey = path.substring(0, separator);
            final String field = path.substring(separator + 1);
            if (!(field.equals("type") || field.equals("id") || field.equals("owner"))) {
                throw new IllegalStateException("Unknown field '" + path + "' in placeable registry " + file);
            }
            if (parseKey(storedKey).isEmpty()) {
                throw new IllegalStateException("Invalid placement location key '" + storedKey + "' in " + file);
            }
            rowFields.computeIfAbsent(storedKey, ignored -> new java.util.HashSet<>()).add(field);
        }
        for (final Map.Entry<String, java.util.Set<String>> row : rowFields.entrySet()) {
            if (!row.getValue().contains("type") || !row.getValue().contains("id")) {
                throw new IllegalStateException("Incomplete placement record '" + row.getKey() + "' in " + file);
            }
        }
        if (rowFields.isEmpty() && !hasRegistryVersion) {
            throw new IllegalStateException("Empty placeable registry " + file
                    + "; its prior contents may have been truncated, so the original file was preserved and CoreMC will fail closed.");
        }
        boolean rewriteLegacyKeys = false;
        for (final String path : yaml.getKeys(true)) {
            if (!path.endsWith(".type")) {
                continue;
            }
            final String storedKey = path.substring(0, path.length() - ".type".length());
            final RegistryKey key = parseKey(storedKey).orElseThrow(() ->
                    new IllegalStateException("Invalid placement location key '" + storedKey + "' in " + file));
            final Type type = Type.byTag(yaml.getString(path, ""));
            final String id = yaml.getString(storedKey + ".id", "");
            final String rawOwner = yaml.getString(storedKey + ".owner", "");
            final UUID owner = uuidOf(rawOwner);
            if (type == null || id.isEmpty() || (!rawOwner.isBlank() && owner == null)) {
                throw new IllegalStateException("Invalid placement record '" + storedKey + "' in " + file);
            }
            final String canonicalKey = keyOf(key.worldName(), key.x(), key.y(), key.z());
            final Placement placement = new Placement(
                    key.worldName(), type, id, owner, key.x(), key.y(), key.z());
            final Placement existing = placements.putIfAbsent(canonicalKey, placement);
            if (existing != null && !existing.equals(placement)) {
                throw new IllegalStateException("Conflicting placement records for '" + canonicalKey + "' in " + file);
            }
            rewriteLegacyKeys |= !canonicalKey.equals(storedKey);
        }
        registryReady = true;
        if (rewriteLegacyKeys && !save()) {
            plugin.getLogger().severe("Could not migrate legacy placeable registry keys; loaded placements remain in memory.");
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
    public boolean save() {
        if (!registryReady) {
            plugin.getLogger().severe("Refusing to overwrite an unreadable placeable registry: " + file);
            return false;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("registry-version", 1);
        for (final Map.Entry<String, Placement> entry : placements.entrySet()) {
            final String key = entry.getKey();
            final Placement p = entry.getValue();
            yaml.set(key + ".type", p.type().tag());
            yaml.set(key + ".id", p.id());
            if (p.owner() != null) {
                yaml.set(key + ".owner", p.owner().toString());
            }
        }
        final java.nio.file.Path target = file.toPath();
        final java.nio.file.Path temporary = target.resolveSibling(file.getName() + ".tmp");
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save placements: " + e.getMessage());
            return false;
        }
        return true;
    }
    public boolean register(final String worldName, final int x, final int y, final int z,
                            final Type type, final String id, final UUID owner) {
        final String key = keyOf(worldName, x, y, z);
        final Placement next = new Placement(worldName, type, id, owner, x, y, z);
        final Placement previous = placements.put(key, next);
        if (!next.equals(previous) && !save()) {
            if (previous == null) {
                placements.remove(key);
            } else {
                placements.put(key, previous);
            }
            return false;
        }
        return true;
    }

    /** Restore a placement after a cancelled break, retaining it in memory if disk I/O fails. */
    public boolean restoreAfterCancelledBreak(final String worldName, final Placement placement) {
        final Placement restored = new Placement(worldName, placement.type(), placement.id(), placement.owner(),
                placement.x(), placement.y(), placement.z());
        placements.put(keyOf(worldName, placement.x(), placement.y(), placement.z()), restored);
        return save();
    }
    public Optional<Placement> at(final Location location) {
        if (location.getWorld() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(placements.get(keyOf(
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ())));
    }

    public boolean unregister(final Location location) {
        return unregisterAll(java.util.List.of(location));
    }

    /** Removes a batch of locations and writes the registry once if anything changed. */
    public boolean unregisterAll(final java.util.Collection<Location> locations) {
        final Map<String, Placement> removed = new HashMap<>();
        for (final Location location : locations) {
            if (location == null || location.getWorld() == null) {
                continue;
            }
            final String key = keyOf(
                    location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
            final Placement placement = placements.remove(key);
            if (placement != null) {
                removed.put(key, placement);
            }
        }
        if (removed.isEmpty() || save()) {
            return true;
        }
        placements.putAll(removed);
        return false;
    }
    public int size() {
        return placements.size();
    }

    /** Read-only view of the registry (keys are encoded, YAML-safe location ids); used by the spawner retune. */
    public Map<String, Placement> placements() {
        return java.util.Collections.unmodifiableMap(placements);
    }
}
