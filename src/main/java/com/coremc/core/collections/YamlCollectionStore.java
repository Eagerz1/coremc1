package com.coremc.core.collections;

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
 * YAML Collection store ({@code plugins/CoreMC/collections-data.yml}),
 * written atomically like every other CoreMC store.
 *
 * <p>This file is <b>permanent player data</b>: season resets never
 * touch it, and a corrupt entry is skipped with a warning rather than
 * wiping a player's lifetime totals.</p>
 */
public final class YamlCollectionStore implements CollectionStore {

    private final Path file;
    private final Logger logger;

    public YamlCollectionStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public Map<UUID, CollectionProfile> load() throws IOException {
        final Map<UUID, CollectionProfile> profiles = new LinkedHashMap<>();
        final YamlConfiguration yaml = read();
        final ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return profiles;
        }
        for (final String rawUuid : root.getKeys(false)) {
            final UUID id;
            try {
                id = UUID.fromString(rawUuid);
            } catch (final IllegalArgumentException badId) {
                warn("Skipping collection data for invalid uuid " + rawUuid);
                continue;
            }
            final ConfigurationSection section = root.getConfigurationSection(rawUuid);
            if (section == null) {
                continue;
            }
            final CollectionProfile profile = new CollectionProfile();
            final ConfigurationSection amounts = section.getConfigurationSection("amounts");
            if (amounts != null) {
                for (final String entryId : amounts.getKeys(false)) {
                    profile.set(entryId, amounts.getLong(entryId));
                }
            }
            final ConfigurationSection discoveries = section.getConfigurationSection("discoveries");
            if (discoveries != null) {
                for (final String entryId : discoveries.getKeys(false)) {
                    profile.putDiscovery(entryId, new DiscoveryRecord(
                            discoveries.getLong(entryId + ".count"),
                            discoveries.getLong(entryId + ".first")));
                }
            }
            for (final String claim : section.getStringList("claimed")) {
                profile.putClaim(claim);
            }
            for (final String unlock : section.getStringList("unlocks")) {
                profile.unlock(unlock);
            }
            if (!profile.empty()) {
                profiles.put(id, profile);
            }
        }
        return profiles;
    }

    @Override
    public void save(final Map<UUID, CollectionProfile> profiles) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, CollectionProfile> entry : profiles.entrySet()) {
            final CollectionProfile profile = entry.getValue();
            if (profile == null || profile.empty()) {
                continue;
            }
            final String base = "players." + entry.getKey();
            for (final Map.Entry<String, Long> amount : profile.amounts().entrySet()) {
                yaml.set(base + ".amounts." + amount.getKey(), amount.getValue());
            }
            for (final Map.Entry<String, DiscoveryRecord> discovery
                    : profile.discoveries().entrySet()) {
                yaml.set(base + ".discoveries." + discovery.getKey() + ".count",
                        discovery.getValue().count());
                yaml.set(base + ".discoveries." + discovery.getKey() + ".first",
                        discovery.getValue().first());
            }
            final List<String> claims = new ArrayList<>(profile.claims());
            claims.sort(String::compareTo);
            if (!claims.isEmpty()) {
                yaml.set(base + ".claimed", claims);
            }
            final List<String> unlocks = new ArrayList<>(profile.unlocks());
            unlocks.sort(String::compareTo);
            if (!unlocks.isEmpty()) {
                yaml.set(base + ".unlocks", unlocks);
            }
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private YamlConfiguration read() {
        final YamlConfiguration yaml = new YamlConfiguration();
        if (!Files.isRegularFile(file)) {
            return yaml;
        }
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final Exception failure) {
            warn("Corrupt " + file + " — collections start empty: " + failure.getMessage());
        }
        return yaml;
    }

    private void warn(final String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
