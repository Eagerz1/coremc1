package com.coremc.core.essence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * YAML essence store: {@code plugins/CoreMC/essence-balances.yml} mapping
 * player UUIDs to {@code {slayer, mining, farming, kills}}, written
 * atomically (temp file + move) exactly like the economy store.
 */
public final class YamlEssenceStore implements EssenceStore {

    private final Path file;
    private final Logger logger;

    public YamlEssenceStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public Map<UUID, EssenceProfile> loadAll() throws IOException {
        final Map<UUID, EssenceProfile> profiles = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return profiles;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            logger.warning("Corrupt essence file " + file + " — starting fresh: " + exception.getMessage());
            return profiles;
        }
        for (final String key : yaml.getKeys(false)) {
            final ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                logger.warning("Skipping bad essence entry '" + key + "' in " + file + ".");
                continue;
            }
            try {
                profiles.put(UUID.fromString(key), new EssenceProfile(
                        section.getLong("slayer"),
                        section.getLong("mining"),
                        section.getLong("farming"),
                        section.getLong("kills")));
            } catch (final IllegalArgumentException exception) {
                logger.warning("Skipping bad essence entry '" + key + "' in " + file + ".");
            }
        }
        return profiles;
    }

    @Override
    public void saveAll(final Map<UUID, EssenceProfile> profiles) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, EssenceProfile> entry : profiles.entrySet()) {
            final EssenceProfile profile = entry.getValue();
            yaml.set(entry.getKey().toString() + ".slayer", profile.slayer);
            yaml.set(entry.getKey().toString() + ".mining", profile.mining);
            yaml.set(entry.getKey().toString() + ".farming", profile.farming);
            yaml.set(entry.getKey().toString() + ".kills", profile.kills);
        }
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
