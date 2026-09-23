package com.coremc.core.island;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Island buff persistence ({@code plugins/CoreMC/buffs.yml}): island
 * id -> buff id -> expiry epoch millis. Written atomically after each
 * change, like every other CoreMC store.
 */
public final class YamlBuffStore {

    private final Path file;
    private final Logger logger;

    public YamlBuffStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Loads all active buffs; corrupt entries are skipped with a warning. */
    public Map<UUID, Map<String, Long>> load() throws IOException {
        final Map<UUID, Map<String, Long>> loaded = new LinkedHashMap<>();
        final YamlConfiguration yaml = new YamlConfiguration();
        if (!Files.isRegularFile(file)) {
            return loaded;
        }
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            logger.warning("Corrupt buff data " + file + " — starting fresh: " + exception.getMessage());
            return loaded;
        }
        final ConfigurationSection root = yaml.getConfigurationSection("islands");
        if (root == null) {
            return loaded;
        }
        for (final String islandKey : root.getKeys(false)) {
            try {
                final UUID islandId = UUID.fromString(islandKey);
                final Map<String, Long> buffs = new LinkedHashMap<>();
                final ConfigurationSection section = root.getConfigurationSection(islandKey);
                if (section != null) {
                    for (final String buffId : section.getKeys(false)) {
                        buffs.put(buffId, section.getLong(buffId, 0L));
                    }
                }
                loaded.put(islandId, buffs);
            } catch (final IllegalArgumentException exception) {
                logger.warning("Skipping bad buff island '" + islandKey + "' in " + file + ".");
            }
        }
        return loaded;
    }

    /** Persists all active buffs atomically. */
    public void save(final Map<UUID, Map<String, Long>> buffs) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, Map<String, Long>> island : buffs.entrySet()) {
            for (final Map.Entry<String, Long> buff : island.getValue().entrySet()) {
                yaml.set("islands." + island.getKey() + "." + buff.getKey(), buff.getValue());
            }
        }
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
