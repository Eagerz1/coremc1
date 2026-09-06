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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * YAML island store: one file per owner under
 * {@code plugins/CoreMC/islands/<ownerUuid>.yml}, written atomically
 * (temp file + move) so crashes cannot corrupt island data.
 */
public final class YamlIslandDataStore implements IslandDataStore {

    private final Path directory;

    public YamlIslandDataStore(final Path directory) {
        this.directory = directory;
    }

    @Override
    public Collection<Island> loadAll() throws IOException {
        final Collection<Island> islands = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return islands;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.yml")) {
            for (final Path file : stream) {
                islands.add(read(file));
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

    private Island read(final Path file) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IOException("Corrupt island file " + file, exception);
        }
        final ConfigurationSection section = yaml.getConfigurationSection("island");
        if (section == null) {
            throw new IOException("Island file without 'island' section: " + file);
        }
        try {
            return Island.fromMap(section.getValues(false));
        } catch (final IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Invalid island data in " + file, exception);
        }
    }

    private Path fileFor(final UUID owner) {
        return directory.resolve(owner.toString() + ".yml");
    }
}
