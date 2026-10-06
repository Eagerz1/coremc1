package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/** Persistent time-limited leaderboard disqualifications keyed by island owner. */
public final class IslandDisqualificationService {
    public record Entry(UUID owner, long expiresAtMillis, String reason, String actor) {}
    private final CoreMCPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public IslandDisqualificationService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "island-disqualifications.yml");
    }

    public void load() {
        entries.clear();
        if (!file.isFile()) return;
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        final var section = yaml.getConfigurationSection("islands");
        if (section == null) return;
        for (final String key : section.getKeys(false)) {
            try {
                final UUID owner = UUID.fromString(key);
                final var item = section.getConfigurationSection(key);
                if (item == null) continue;
                entries.put(owner, new Entry(owner, item.getLong("expires-at"),
                        item.getString("reason", "No reason supplied"), item.getString("actor", "console")));
            } catch (final IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid island DQ owner id: " + key);
            }
        }
    }

    public boolean isActive(final UUID owner) {
        final Entry entry = entries.get(owner);
        return entry != null && entry.expiresAtMillis() > System.currentTimeMillis();
    }

    public Entry disqualify(final UUID owner, final int weeks, final String reason, final String actor) throws IOException {
        if (weeks < 1 || weeks > 8) throw new IllegalArgumentException("weeks must be 1..8");
        final Entry entry = new Entry(owner,
                System.currentTimeMillis() + weeks * 7L * 24L * 60L * 60L * 1000L,
                reason, actor);
        entries.put(owner, entry);
        save();
        return entry;
    }

    private void save() throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Entry entry : entries.values()) {
            final String base = "islands." + entry.owner();
            yaml.set(base + ".expires-at", entry.expiresAtMillis());
            yaml.set(base + ".reason", entry.reason());
            yaml.set(base + ".actor", entry.actor());
        }
        file.getParentFile().mkdirs();
        yaml.save(file);
    }
}
