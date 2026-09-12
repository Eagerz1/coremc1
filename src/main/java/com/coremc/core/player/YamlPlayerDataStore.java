package com.coremc.core.player;

import com.coremc.core.util.RawYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * YAML-file player profile store: one small file per player under
 * {@code plugins/CoreMC/profiles/<uuid>.yml}.
 *
 * Serialisation goes through RAW SnakeYAML (never Bukkit's
 * {@code YamlConfiguration}): profile maps contain dotted identifiers
 * (enchant ids such as {@code miner.treasure-miner}, stats such as
 * {@code crate-pity:sky}) which Bukkit treats as nested paths and would
 * mangle into sections on save and misread on load, silently wiping
 * paid progression on restart.
 *
 * Writes go to a temporary file first and are then moved atomically,
 * so a crash mid-save can never leave a corrupt profile behind.
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
        final Map<String, Object> root;
        try {
            root = RawYaml.loadMap(file.toFile());
        } catch (final RawYaml.YamlLoadException exception) {
            throw new IOException("Corrupt profile file " + file, exception.getCause());
        }
        final Object profileSection = root.get("profile");
        if (!(profileSection instanceof Map<?, ?> rawProfile)) {
            throw new IOException("Profile file without 'profile' section: " + file);
        }
        return Optional.of(PlayerProfile.fromMap(uuid, RawYaml.toStringKeyedMap(rawProfile)));
    }

    @Override
    public void save(final PlayerProfile profile) throws IOException {
        Files.createDirectories(directory);
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("profile", profile.toMap());
        RawYaml.writeAtomic(fileFor(profile.uuid()), RawYaml.dump(root));
    }

    private Path fileFor(final UUID uuid) {
        return directory.resolve(uuid.toString() + ".yml");
    }

    /**
     * Username index lives next to the profile files in
     * {@code profiles/usernames.yml}: {@code name(lowercase): uuid}. It lets
     * admin commands resolve offline players by name without relying on
     * Bukkit's usercache alone.
     */
    @Override
    public Map<String, UUID> loadNameIndex() throws IOException {
        final Path file = indexFile();
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        final Map<String, UUID> index = new java.util.HashMap<>();
        final Map<String, Object> raw;
        try {
            raw = RawYaml.loadMap(file.toFile());
        } catch (final RawYaml.YamlLoadException exception) {
            throw new IOException("Corrupt username index " + file, exception.getCause());
        }
        for (final Map.Entry<String, Object> entry : raw.entrySet()) {
            try {
                index.put(entry.getKey().toLowerCase(java.util.Locale.ROOT),
                        UUID.fromString(String.valueOf(entry.getValue())));
            } catch (final IllegalArgumentException | NullPointerException ignored) {
                // skip corrupt entries; the index is rebuildable from joins
            }
        }
        return index;
    }

    @Override
    public void saveNameIndex(final Map<String, UUID> index) throws IOException {
        Files.createDirectories(directory);
        final Map<String, Object> raw = new LinkedHashMap<>();
        for (final Map.Entry<String, UUID> entry : index.entrySet()) {
            raw.put(entry.getKey(), entry.getValue().toString());
        }
        RawYaml.writeAtomic(indexFile(), RawYaml.dump(raw));
    }

    private Path indexFile() {
        return directory.resolve("usernames.yml");
    }
}
