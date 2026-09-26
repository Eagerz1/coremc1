package com.coremc.core.crate;

import com.coremc.core.reward.RewardDef;
import com.coremc.core.reward.RewardParser;
import com.coremc.core.reward.WeightedTable;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and validates {@code crates.yml}: CoreMC's five physical crate
 * keys (Vote, River, Sky, Crimson, Boost — ids, names, materials,
 * Credit prices) and the crates they open (display, reward tables,
 * weights, sounds, particles, rare broadcasts).
 *
 * <p>Like every CoreMC config, all problems are collected into one
 * loud exception; the plugin disables the store systems with a single
 * log line instead of half-working or corrupting purchases.</p>
 */
public final class CrateConfig {

    private static final String FILE_NAME = "crates.yml";

    private final JavaPlugin plugin;
    private final Map<String, KeyDef> keys = new LinkedHashMap<>();
    private final Map<String, CrateDef> crates = new LinkedHashMap<>();
    private boolean enabled = true;

    public CrateConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** A disabled config: no keys, no crates, menus say so instead of crashing. */
    public static CrateConfig disabled() {
        final CrateConfig config = new CrateConfig(null);
        config.enabled = false;
        return config;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Extracts the default crates.yml on first run, then parses and validates. */
    public void load() {
        final File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (final Exception exception) {
            throw new IllegalArgumentException("cannot read " + FILE_NAME + ": "
                    + exception.getMessage(), exception);
        }
        parse(yaml, null);
    }

    /**
     * Parses and validates a YAML document (also used by the tests).
     *
     * @param lootboxIds valid lootbox ids for cross-referencing lootbox
     *                   rewards inside crate pools (null skips the check)
     */
    public void parse(final YamlConfiguration yaml, final Set<String> lootboxIds) {
        final List<String> problems = new ArrayList<>();
        keys.clear();
        crates.clear();

        parseKeys(yaml, problems);
        parseCrates(yaml, lootboxIds, problems);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("broken " + FILE_NAME + ": "
                    + String.join("; ", problems));
        }
    }

    private void parseKeys(final YamlConfiguration yaml, final List<String> problems) {
        final ConfigurationSection section = yaml.getConfigurationSection("keys");
        if (section == null) {
            problems.add("missing keys section");
            return;
        }
        for (final String rawId : section.getKeys(false)) {
            final String id = rawId.toLowerCase(Locale.ROOT);
            final ConfigurationSection entry = section.getConfigurationSection(rawId);
            if (entry == null) {
                problems.add("keys." + rawId + ": not a section");
                continue;
            }
            final String name = entry.getString("name", "");
            if (name.isBlank()) {
                problems.add("keys." + rawId + ": missing name");
                continue;
            }
            final Material material = Material.matchMaterial(
                    entry.getString("material", "TRIPWIRE_HOOK"));
            if (material == null || material == Material.AIR) {
                problems.add("keys." + rawId + ": unknown material '"
                        + entry.getString("material") + "'");
                continue;
            }
            final long price = entry.getLong("price", -1);
            if (price < 0) {
                problems.add("keys." + rawId + ": price must be >= 0 Credits");
                continue;
            }
            if (keys.containsKey(id)) {
                problems.add("keys." + rawId + ": duplicate key id");
                continue;
            }
            keys.put(id, new KeyDef(id, name, material, price,
                    entry.getBoolean("sellable", true), entry.getStringList("description")));
        }
        if (keys.isEmpty()) {
            problems.add("no valid keys configured");
        }
    }

    private void parseCrates(final YamlConfiguration yaml, final Set<String> lootboxIds,
                             final List<String> problems) {
        final ConfigurationSection section = yaml.getConfigurationSection("crates");
        if (section == null) {
            problems.add("missing crates section");
            return;
        }
        final Set<String> keyIds = new LinkedHashSet<>(keys.keySet());
        for (final String rawId : section.getKeys(false)) {
            final String id = rawId.toLowerCase(Locale.ROOT);
            final ConfigurationSection entry = section.getConfigurationSection(rawId);
            if (entry == null) {
                problems.add("crates." + rawId + ": not a section");
                continue;
            }
            final String name = entry.getString("name", "");
            if (name.isBlank()) {
                problems.add("crates." + rawId + ": missing name");
                continue;
            }
            final Material material = Material.matchMaterial(
                    entry.getString("display-material", "CHEST"));
            if (material == null || material == Material.AIR) {
                problems.add("crates." + rawId + ": unknown display-material '"
                        + entry.getString("display-material") + "'");
                continue;
            }
            final String keyId = entry.getString("key", "").toLowerCase(Locale.ROOT);
            if (!keys.containsKey(keyId)) {
                problems.add("crates." + rawId + ": unknown key '" + keyId + "'");
                continue;
            }
            final List<RewardDef> pool = RewardParser.parse(
                    entry.getConfigurationSection("rewards"), "crates." + rawId + ".rewards",
                    keyIds, lootboxIds, problems);
            if (pool.isEmpty()) {
                continue;
            }
            if (crates.containsKey(id)) {
                problems.add("crates." + rawId + ": duplicate crate id");
                continue;
            }
            crates.put(id, new CrateDef(id, name, material, keyId, new WeightedTable(pool),
                    entry.getString("open-sound", "BLOCK_CHEST_OPEN"),
                    entry.getString("win-sound", "ENTITY_PLAYER_LEVELUP"),
                    entry.getString("particle", "HAPPY_VILLAGER"),
                    entry.getBoolean("broadcast-rare", true)));
        }
        if (crates.isEmpty()) {
            problems.add("no valid crates configured");
        }
    }

    // ------------------------------------------------------------------
    // access
    // ------------------------------------------------------------------

    public List<KeyDef> keys() {
        return List.copyOf(keys.values());
    }

    public KeyDef key(final String id) {
        return id == null ? null : keys.get(id.toLowerCase(Locale.ROOT));
    }

    public List<CrateDef> crates() {
        return List.copyOf(crates.values());
    }

    public CrateDef crate(final String id) {
        return id == null ? null : crates.get(id.toLowerCase(Locale.ROOT));
    }

    /** The crate a key opens (null when the key opens nothing). */
    public CrateDef crateForKey(final String keyId) {
        for (final CrateDef crate : crates.values()) {
            if (crate.keyId().equals(keyId)) {
                return crate;
            }
        }
        return null;
    }
}
