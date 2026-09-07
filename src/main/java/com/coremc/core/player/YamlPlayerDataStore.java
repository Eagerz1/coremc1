package com.coremc.core.player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * YAML-file player profile store: one small file per player under
 * {@code plugins/CoreMC/profiles/<uuid>.yml}.
 *
 * Writes go to a temporary file first and are then moved atomically, so a
 * crash mid-save can never leave a corrupt profile behind.
 */
public final class YamlPlayerDataStore implements PlayerDataStore {

    private final Path directory;

    public YamlPlayerDataStore(final Path directory) {
        this.directory = directory;
    }

    @Override
    public Optional<PlayerProfile> load(final UUID uuid) throws IOException {
        final Path file = fileFor(uuid);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IOException("Corrupt profile file " + file, exception);
        }

        final ConfigurationSection root = yaml.getConfigurationSection("profile");
        if (root == null) {
            throw new IOException("Profile file without 'profile' section: " + file);
        }
        return Optional.of(PlayerProfile.fromMap(uuid, deepValues(root)));
    }

    /** Nested values (e.g. cosmetics) come back as ConfigurationSection via
     *  getValues() — convert recursively to plain Maps for the model. */
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

    @Override
    public void save(final PlayerProfile profile) throws IOException {
        Files.createDirectories(directory);

        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<String, Object> entry : profile.toMap().entrySet()) {
            yaml.set("profile." + entry.getKey(), entry.getValue());
        }

        final Path target = fileFor(profile.uuid());
        final Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        Files.write(temp, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path fileFor(final UUID uuid) {
        return directory.resolve(uuid.toString() + ".yml");
    }

    /**
     * Username index lives next to the profiles directory in
     * {@code usernames.yml}: {@code name(lowercase): uuid}. It lets
     * admin commands resolve offline players by name without relying on
     * Bukkit's usercache alone.
     */
    @Override
    public Map<String, UUID> loadNameIndex() throws IOException {
        final Path file = indexFile();
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IOException("Corrupt username index " + file, exception);
        }
        final Map<String, UUID> index = new java.util.HashMap<>();
        for (final String name : yaml.getKeys(false)) {
            final String raw = yaml.getString(name);
            try {
                if (raw != null) {
                    index.put(name.toLowerCase(java.util.Locale.ROOT), UUID.fromString(raw));
                }
            } catch (final IllegalArgumentException | NullPointerException ignored) {
                // skip corrupt entries; the index is rebuildable from joins
            }
        }
        return index;
    }

    @Override
    public void saveNameIndex(final Map<String, UUID> index) throws IOException {
        Files.createDirectories(directory);
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<String, UUID> entry : index.entrySet()) {
            yaml.set(entry.getKey(), entry.getValue().toString());
        }
        final Path target = indexFile();
        final Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path indexFile() {
        return directory.resolve("usernames.yml");
    }
}
