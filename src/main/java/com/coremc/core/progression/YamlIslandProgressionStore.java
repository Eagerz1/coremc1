package com.coremc.core.progression;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Atomic YAML persistence for island progression profiles. */
public final class YamlIslandProgressionStore {

    private final Path file;
    private final Logger logger;

    public YamlIslandProgressionStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public Map<UUID, IslandProgressionProfile> loadAll() throws IOException {
        final Map<UUID, IslandProgressionProfile> profiles = new LinkedHashMap<>();
        final YamlConfiguration yaml = load();
        final ConfigurationSection root = yaml.getConfigurationSection("islands");
        if (root == null) {
            return profiles;
        }
        for (final String key : root.getKeys(false)) {
            try {
                final UUID islandId = UUID.fromString(key);
                final ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                final IslandProgressionProfile profile = new IslandProgressionProfile(islandId);
                profile.setXp(section.getLong("xp", 0L));
                profile.setSkyTokens(section.getLong("sky-tokens", 0L));
                loadMastery(profile, section.getConfigurationSection("mastery"));
                for (final String module : section.getStringList("owned-modules")) {
                    profile.addOwnedModule(module);
                }
                profile.setActiveModules(new LinkedHashSet<>(section.getStringList("active-modules")));
                profile.setLastModuleSwapAt(section.getLong("last-module-swap-at", 0L));
                profile.setFortuneFocus(section.getString("fortune.focus", ""),
                        section.getLong("fortune.selected-at", 0L));
                profile.setMomentum(section.getDouble("momentum", 0.0D));
                loadLongMap(profile, section.getConfigurationSection("active-states"), true);
                loadLongMap(profile, section.getConfigurationSection("cooldowns"), false);
                loadDiscoveries(profile, section.getConfigurationSection("discoveries"));
                loadWindows(profile, section.getConfigurationSection("windows"));
                profiles.put(islandId, profile);
            } catch (final RuntimeException exception) {
                logger.warning("Skipping bad island progression entry '" + key + "' in "
                        + file + ": " + exception.getMessage());
            }
        }
        return profiles;
    }

    private void loadMastery(final IslandProgressionProfile profile, final ConfigurationSection root) {
        if (root == null) {
            return;
        }
        for (final String branch : root.getKeys(false)) {
            final ConfigurationSection branchSection = root.getConfigurationSection(branch);
            if (branchSection == null) {
                continue;
            }
            for (final String upgrade : branchSection.getKeys(false)) {
                profile.setMasteryLevel(branch, upgrade, Math.max(0, branchSection.getInt(upgrade, 0)));
            }
        }
    }

    private void loadLongMap(final IslandProgressionProfile profile, final ConfigurationSection root,
                             final boolean activeState) {
        if (root == null) {
            return;
        }
        for (final String id : root.getKeys(false)) {
            if (activeState) {
                profile.setActiveUntil(id, Math.max(0L, root.getLong(id, 0L)));
            } else {
                profile.setCooldownUntil(id, Math.max(0L, root.getLong(id, 0L)));
            }
        }
    }

    private void loadDiscoveries(final IslandProgressionProfile profile, final ConfigurationSection root) {
        if (root == null) {
            return;
        }
        for (final String id : root.getKeys(false)) {
            profile.addDiscovery(id, Math.max(0, root.getInt(id, 0)));
        }
    }

    private void loadWindows(final IslandProgressionProfile profile, final ConfigurationSection root) {
        if (root == null) {
            return;
        }
        for (final String source : root.getKeys(false)) {
            final ConfigurationSection section = root.getConfigurationSection(source);
            if (section == null) {
                continue;
            }
            final IslandProgressionProfile.SourceWindow window =
                    profile.sourceWindow(source, section.getLong("started", 0L));
            window.reset(section.getLong("started", 0L));
            window.addXp(Math.max(0.0D, section.getDouble("xp", 0.0D)));
        }
    }

    public void saveAll(final Map<UUID, IslandProgressionProfile> profiles) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, IslandProgressionProfile> entry : profiles.entrySet()) {
            final String base = "islands." + entry.getKey();
            final IslandProgressionProfile profile = entry.getValue();
            yaml.set(base + ".xp", profile.xp());
            yaml.set(base + ".sky-tokens", profile.skyTokens());
            for (final Map.Entry<String, Map<String, Integer>> branch : profile.mastery().entrySet()) {
                for (final Map.Entry<String, Integer> upgrade : branch.getValue().entrySet()) {
                    yaml.set(base + ".mastery." + branch.getKey() + "." + upgrade.getKey(),
                            upgrade.getValue());
                }
            }
            yaml.set(base + ".owned-modules", profile.ownedModules().stream().toList());
            yaml.set(base + ".active-modules", profile.activeModules().stream().toList());
            yaml.set(base + ".last-module-swap-at", profile.lastModuleSwapAt());
            if (!profile.fortuneFocus().isBlank()) {
                yaml.set(base + ".fortune.focus", profile.fortuneFocus());
                yaml.set(base + ".fortune.selected-at", profile.fortuneSelectedAt());
            }
            yaml.set(base + ".momentum", profile.momentum());
            for (final Map.Entry<String, Long> active : profile.activeStates().entrySet()) {
                yaml.set(base + ".active-states." + active.getKey(), active.getValue());
            }
            for (final Map.Entry<String, Long> cooldown : profile.cooldowns().entrySet()) {
                yaml.set(base + ".cooldowns." + cooldown.getKey(), cooldown.getValue());
            }
            for (final Map.Entry<String, Integer> discovery : profile.discoveries().entrySet()) {
                yaml.set(base + ".discoveries." + discovery.getKey(), discovery.getValue());
            }
            for (final Map.Entry<String, IslandProgressionProfile.SourceWindow> window
                    : profile.sourceWindows().entrySet()) {
                yaml.set(base + ".windows." + window.getKey() + ".started", window.getValue().startedAt());
                yaml.set(base + ".windows." + window.getKey() + ".xp", window.getValue().xp());
            }
        }
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
            logger.warning("Corrupt island progression data " + file
                    + " — starting fresh: " + exception.getMessage());
        }
        return yaml;
    }
}
