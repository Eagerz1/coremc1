package com.coremc.core.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Raw SnakeYAML loading/saving that preserves dotted map keys verbatim.
 *
 * Bukkit's {@code YamlConfiguration} treats '.' as a path separator on
 * BOTH parse and write: an enchant id like {@code miner.treasure-miner}
 * silently becomes a nested {@code miner -> treasure-miner} section,
 * which empties catalogues and destroys persisted data. Everything that
 * stores identifiers containing dots (enchants.yml, profile enchant
 * levels, ...) must go through this class instead.
 *
 * The class is deliberately Bukkit-free apart from the convenience
 * merge helper, so unit tests can exercise it without a server.
 */
public final class RawYaml {

    private RawYaml() {
    }

    /** Creates a SnakeYAML instance configured the same everywhere (stable output). */
    public static Yaml yaml() {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(4096);
        options.setIndent(2);
        return new Yaml(new SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()), new org.yaml.snakeyaml.representer.Representer(new DumperOptions()), options);
    }

    /** Loads a file into a plain map; missing/empty files yield an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadMap(final File file) {
        if (file == null || !file.isFile()) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            final Object parsed = yaml().load(reader);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) toStringKeyedMap(map);
            }
            return new LinkedHashMap<>();
        } catch (final IOException | RuntimeException exception) {
            throw new YamlLoadException("Failed to read " + file + ": " + exception.getMessage(), exception);
        }
    }

    /** Loads a bundled jar resource into a plain map (empty when absent/empty). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadResource(final JavaPlugin plugin, final String fileName) {
        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) {
                return new LinkedHashMap<>();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                final Object parsed = yaml().load(reader);
                if (parsed instanceof Map<?, ?> map) {
                    return (Map<String, Object>) toStringKeyedMap(map);
                }
                return new LinkedHashMap<>();
            }
        } catch (final IOException | RuntimeException exception) {
            throw new YamlLoadException("Failed to read bundled " + fileName + ": " + exception.getMessage(), exception);
        }
    }

    /** Parses a YAML string into a map (null/empty -> empty map). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(final String content) {
        if (content == null || content.isBlank()) {
            return new LinkedHashMap<>();
        }
        final Object parsed = yaml().load(content);
        if (parsed instanceof Map<?, ?> map) {
            return (Map<String, Object>) toStringKeyedMap(map);
        }
        return new LinkedHashMap<>();
    }

    /** Serialises a map to a block-style YAML string. */
    public static String dump(final Map<String, Object> map) {
        return yaml().dump(map == null ? Map.of() : map);
    }

    /**
     * Atomically writes {@code content} to {@code file} (temp file + move),
     * creating parent directories as needed.
     */
    public static void writeAtomic(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Upgrade-safe defaults merge at the raw-map level.
     *
     * Missing file -> bundled default copied verbatim. Existing file ->
     * every key absent on disk is added from the bundled defaults; disk
     * values always win and nothing is ever deleted. Dotted keys stay
     * literal because no Bukkit path parsing touches them. The file is
     * only rewritten when the merge actually added something.
     *
     * @return true when new default keys were added (and the file rewritten)
     */
    public static boolean mergeNewDefaults(final JavaPlugin plugin, final String fileName) {
        final File file = new File(plugin.getDataFolder(), fileName);
        if (!file.isFile()) {
            plugin.saveResource(fileName, false);
            return false;
        }
        final Map<String, Object> disk;
        final Map<String, Object> defaults;
        try {
            disk = loadMap(file);
            defaults = loadResource(plugin, fileName);
        } catch (final YamlLoadException exception) {
            plugin.getLogger().warning("Could not merge " + fileName + " defaults: " + exception.getMessage());
            return false;
        }
        final List<String> added = new ArrayList<>();
        final boolean changed = mergeMaps(disk, defaults, "", added);
        if (!changed) {
            return false;
        }
        try {
            writeAtomic(file.toPath(), dump(disk));
            plugin.getLogger().info("Merged " + added.size() + " new default key(s) into " + fileName
                    + " (admin values preserved).");
        } catch (final IOException exception) {
            plugin.getLogger().warning("Could not write merged " + fileName + ": " + exception.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Recursively merges {@code defaults} into {@code disk}: maps merge
     * key by key, every other type (including lists) is taken from disk
     * when present. Reports added leaf paths for the caller's log line.
     */
    @SuppressWarnings("unchecked")
    static boolean mergeMaps(final Map<String, Object> disk, final Map<String, Object> defaults,
            final String path, final List<String> added) {
        boolean changed = false;
        for (final Map.Entry<String, Object> entry : defaults.entrySet()) {
            final String key = entry.getKey();
            final Object defaultValue = entry.getValue();
            if (!disk.containsKey(key)) {
                disk.put(key, copy(defaultValue));
                added.add(path.isEmpty() ? key : path + "." + key);
                changed = true;
                continue;
            }
            if (defaultValue instanceof Map<?, ?> defaultMap && disk.get(key) instanceof Map<?, ?> diskMap) {
                final Map<String, Object> diskChildren = (Map<String, Object>) diskMap;
                final Map<String, Object> defaultChildren = toStringKeyedMap(defaultMap);
                final String childPath = path.isEmpty() ? key : path + "." + key;
                if (mergeMaps(diskChildren, defaultChildren, childPath, added)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Deep-copies parsed YAML values so defaults never alias live data. */
    @SuppressWarnings("unchecked")
    static Object copy(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> copy = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), copy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            final List<Object> copy = new ArrayList<>(list.size());
            for (final Object entry : list) {
                copy.add(copy(entry));
            }
            return copy;
        }
        return value;
    }

    /** Recursively converts a parsed YAML map's keys to Strings (YAML scalars parse oddly). */
    public static Map<String, Object> toStringKeyedMap(final Map<?, ?> map) {
        final Map<String, Object> result = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            final String key = String.valueOf(entry.getKey());
            final Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                result.put(key, toStringKeyedMap(nested));
            } else if (value instanceof List<?> list) {
                result.put(key, toStringKeyedList(list));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    private static List<Object> toStringKeyedList(final List<?> list) {
        final List<Object> result = new ArrayList<>(list.size());
        for (final Object value : list) {
            if (value instanceof Map<?, ?> nested) {
                result.add(toStringKeyedMap(nested));
            } else if (value instanceof List<?> nested) {
                result.add(toStringKeyedList(nested));
            } else {
                result.add(value);
            }
        }
        return result;
    }

    /** Unchecked load failure carrying the cause (callers decide how to report). */
    public static final class YamlLoadException extends RuntimeException {
        public YamlLoadException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
