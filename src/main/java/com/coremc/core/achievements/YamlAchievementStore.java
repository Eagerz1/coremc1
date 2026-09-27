package com.coremc.core.achievements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * YAML Achievement store ({@code plugins/CoreMC/achievements-data.yml}),
 * written atomically like every other CoreMC store.
 *
 * <p>Permanent data: season resets never touch it, and the season an
 * Achievement was earned in is stored alongside it so seasonal badges
 * keep their identity forever.</p>
 */
public final class YamlAchievementStore implements AchievementStore {

    private final Path file;
    private final Logger logger;

    public YamlAchievementStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public Map<UUID, AchievementProfile> load() throws IOException {
        final Map<UUID, AchievementProfile> profiles = new LinkedHashMap<>();
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
                warn("Skipping achievement data for invalid uuid " + rawUuid);
                continue;
            }
            final ConfigurationSection section = root.getConfigurationSection(rawUuid);
            if (section == null) {
                continue;
            }
            final AchievementProfile profile = new AchievementProfile();
            final ConfigurationSection counters = section.getConfigurationSection("counters");
            if (counters != null) {
                for (final String key : counters.getKeys(false)) {
                    profile.set(key, counters.getLong(key));
                }
            }
            final ConfigurationSection unique = section.getConfigurationSection("unique");
            if (unique != null) {
                for (final String key : unique.getKeys(false)) {
                    profile.putUnique(key, new LinkedHashSet<>(unique.getStringList(key)));
                }
            }
            final ConfigurationSection earned = section.getConfigurationSection("earned");
            if (earned != null) {
                for (final String key : earned.getKeys(false)) {
                    profile.earn(key, earned.getLong(key + ".at"),
                            earned.getInt(key + ".season", -1));
                }
            }
            for (final String claim : section.getStringList("claimed")) {
                profile.claim(claim);
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
    public void save(final Map<UUID, AchievementProfile> profiles) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, AchievementProfile> entry : profiles.entrySet()) {
            final AchievementProfile profile = entry.getValue();
            if (profile == null || profile.empty()) {
                continue;
            }
            final String base = "players." + entry.getKey();
            for (final Map.Entry<String, Long> counter : profile.counters().entrySet()) {
                yaml.set(base + ".counters." + counter.getKey(), counter.getValue());
            }
            for (final Map.Entry<String, java.util.Set<String>> unique
                    : profile.uniqueKeys().entrySet()) {
                yaml.set(base + ".unique." + unique.getKey(), new ArrayList<>(unique.getValue()));
            }
            for (final Map.Entry<String, Long> earned : profile.earnedTimes().entrySet()) {
                yaml.set(base + ".earned." + earned.getKey() + ".at", earned.getValue());
                final int season = profile.earnedSeason(earned.getKey());
                if (season >= 0) {
                    yaml.set(base + ".earned." + earned.getKey() + ".season", season);
                }
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
            warn("Corrupt " + file + " — achievements start empty: " + failure.getMessage());
        }
        return yaml;
    }

    private void warn(final String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
