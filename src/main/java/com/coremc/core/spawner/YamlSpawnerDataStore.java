package com.coremc.core.spawner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * YAML spawner store ({@code plugins/CoreMC/spawners.yml}), written
 * atomically like the island and balance stores.
 */
public final class YamlSpawnerDataStore implements SpawnerDataStore {

    private final Path file;
    private final Logger logger;

    public YamlSpawnerDataStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public Map<UUID, Integer> loadLuck() throws IOException {
        final Map<UUID, Integer> luck = new LinkedHashMap<>();
        final YamlConfiguration yaml = load();
        final ConfigurationSection section = yaml.getConfigurationSection("luck");
        if (section == null) {
            return luck;
        }
        for (final String key : section.getKeys(false)) {
            try {
                luck.put(UUID.fromString(key), Math.max(0, section.getInt(key)));
            } catch (final IllegalArgumentException exception) {
                logger.warning("Skipping bad luck entry '" + key + "' in " + file + ".");
            }
        }
        return luck;
    }

    @Override
    public List<SpawnerEntry> loadSpawners() throws IOException {
        final List<SpawnerEntry> spawners = new ArrayList<>();
        final YamlConfiguration yaml = load();
        for (final Map<?, ?> raw : yaml.getMapList("spawners")) {
            try {
                final SpawnerEntry entry = new SpawnerEntry(
                        String.valueOf(raw.get("world")),
                        (Integer) raw.get("x"),
                        (Integer) raw.get("y"),
                        (Integer) raw.get("z"),
                        String.valueOf(raw.get("mob")),
                        SpawnerVariant.of(String.valueOf(raw.get("variant"))),
                        UUID.fromString(String.valueOf(raw.get("island"))));
                if (entry.variant() != null) {
                    spawners.add(entry);
                }
            } catch (final RuntimeException exception) {
                logger.warning("Skipping bad spawner entry in " + file + ": " + exception.getMessage());
            }
        }
        return spawners;
    }

    @Override
    public void save(final Map<UUID, Integer> luck, final List<SpawnerEntry> spawners) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, Integer> entry : luck.entrySet()) {
            yaml.set("luck." + entry.getKey(), entry.getValue());
        }
        final List<Map<String, Object>> raw = new ArrayList<>(spawners.size());
        for (final SpawnerEntry entry : spawners) {
            raw.add(Map.of(
                    "world", entry.world(),
                    "x", entry.x(),
                    "y", entry.y(),
                    "z", entry.z(),
                    "mob", entry.mobId(),
                    "variant", entry.variant().name(),
                    "island", entry.islandId().toString()));
        }
        yaml.set("spawners", raw);
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private YamlConfiguration load() throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        if (!Files.isRegularFile(file)) {
            return yaml;
        }
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            logger.warning("Corrupt spawner data " + file + " — starting fresh: " + exception.getMessage());
        }
        return yaml;
    }
}
