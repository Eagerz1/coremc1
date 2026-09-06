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
        return Optional.of(PlayerProfile.fromMap(uuid, root.getValues(false)));
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
}
