package com.coremc.core.island;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * YAML island store: one file per owner under
 * {@code plugins/CoreMC/islands/<ownerUuid>.yml}, written atomically
 * (temp file + move) so crashes cannot corrupt island data.
 */
public final class YamlIslandDataStore implements IslandDataStore {

    private final Path directory;
    private final Logger logger;

    public YamlIslandDataStore(final Path directory, final Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    @Override
    public Collection<Island> loadAll() throws IOException {
        final Collection<Island> islands = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return islands;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.yml")) {
            for (final Path file : stream) {
                try {
                    islands.add(read(file));
                } catch (final RuntimeException exception) {
                    logger.warning("Skipping corrupt island file " + file + ": " + exception.getMessage());
                }
            }
        }
        return islands;
    }

    @Override
    public void save(final Island island) throws IOException {
        Files.createDirectories(directory);

        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<String, Object> entry : island.toMap().entrySet()) {
            yaml.set("island." + entry.getKey(), entry.getValue());
        }

        final Path target = fileFor(island.owner());
        final Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void delete(final UUID owner) throws IOException {
        Files.deleteIfExists(fileFor(owner));
    }

    private Island read(final Path file) {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalArgumentException("unreadable: " + exception.getMessage(), exception);
        }
        final ConfigurationSection section = yaml.getConfigurationSection("island");
        if (section == null) {
            throw new IllegalArgumentException("no 'island' section");
        }
        return Island.fromMap(deepValues(section));
    }

    /**
     * getValues() returns nested entries as ConfigurationSection, not Map —
     * convert recursively so model code never touches Bukkit config types.
     */
    static Map<String, Object> deepValues(final ConfigurationSection section) {
        final Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            if (entry.getValue() instanceof ConfigurationSection nested) {
                values.put(entry.getKey(), deepValues(nested));
            } else {
                values.put(entry.getKey(), entry.getValue());
            }
        }
        return values;
    }

    private Path fileFor(final UUID owner) {
        return directory.resolve(owner.toString() + ".yml");
    }
}
