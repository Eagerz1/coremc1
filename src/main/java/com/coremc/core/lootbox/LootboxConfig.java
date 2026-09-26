package com.coremc.core.lootbox;

import com.coremc.core.reward.RewardDef;
import com.coremc.core.reward.RewardParser;
import com.coremc.core.reward.WeightedTable;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and validates {@code lootboxes.yml}: the three store lootboxes
 * (Core, Monthly, Seasonal), each with a Credit price, reward category
 * lines for the item lore, a weighted normal pool (8 rolls) and a
 * weighted rare pool (1 guaranteed roll).
 *
 * <p>All problems are collected into one loud exception — a broken
 * file disables the store systems instead of corrupting purchases.</p>
 */
public final class LootboxConfig {

    private static final String FILE_NAME = "lootboxes.yml";

    private final JavaPlugin plugin;
    private final Map<String, LootboxDef> lootboxes = new LinkedHashMap<>();
    private boolean enabled = true;

    public LootboxConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** A disabled config: no lootboxes, menus say so instead of crashing. */
    public static LootboxConfig disabled() {
        final LootboxConfig config = new LootboxConfig(null);
        config.enabled = false;
        return config;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Extracts the default lootboxes.yml on first run, then parses and validates. */
    public void load(final Set<String> keyIds) {
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
        parse(yaml, keyIds);
    }

    /** Parses and validates a YAML document (also used by the tests). */
    public void parse(final YamlConfiguration yaml, final Set<String> keyIds) {
        final List<String> problems = new ArrayList<>();
        lootboxes.clear();

        final ConfigurationSection section = yaml.getConfigurationSection("lootboxes");
        if (section == null) {
            throw new IllegalArgumentException("broken " + FILE_NAME
                    + ": missing lootboxes section");
        }
        // lootbox rewards may reference other lootboxes (e.g. a Monthly
        // rare paying a Core Lootbox) — collect all ids first
        final Set<String> boxIds = new java.util.LinkedHashSet<>();
        for (final String rawId : section.getKeys(false)) {
            boxIds.add(rawId.toLowerCase(Locale.ROOT));
        }
        for (final String rawId : section.getKeys(false)) {
            parseBox(section, rawId, keyIds, boxIds, problems);
        }
        if (lootboxes.isEmpty()) {
            problems.add("no valid lootboxes configured");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("broken " + FILE_NAME + ": "
                    + String.join("; ", problems));
        }
    }

    private void parseBox(final ConfigurationSection section, final String rawId,
                          final Set<String> keyIds, final Set<String> boxIds,
                          final List<String> problems) {
        final String id = rawId.toLowerCase(Locale.ROOT);
        final ConfigurationSection entry = section.getConfigurationSection(rawId);
        if (entry == null) {
            problems.add("lootboxes." + rawId + ": not a section");
            return;
        }
        final String name = entry.getString("name", "");
        if (name.isBlank()) {
            problems.add("lootboxes." + rawId + ": missing name");
            return;
        }
        final long price = entry.getLong("price", -1);
        if (price < 0) {
            problems.add("lootboxes." + rawId + ": price must be >= 0 Credits");
            return;
        }
        final List<RewardDef> normal = RewardParser.parse(
                entry.getConfigurationSection("normal-rewards"),
                "lootboxes." + rawId + ".normal-rewards", keyIds, boxIds, problems);
        final List<RewardDef> rare = RewardParser.parse(
                entry.getConfigurationSection("rare-rewards"),
                "lootboxes." + rawId + ".rare-rewards", keyIds, boxIds, problems);
        if (normal.isEmpty() || rare.isEmpty()) {
            return;
        }
        if (lootboxes.containsKey(id)) {
            problems.add("lootboxes." + rawId + ": duplicate lootbox id");
            return;
        }
        lootboxes.put(id, new LootboxDef(id, name, price,
                entry.getStringList("categories"),
                new WeightedTable(normal), new WeightedTable(rare)));
    }

    // ------------------------------------------------------------------
    // access
    // ------------------------------------------------------------------

    public List<LootboxDef> all() {
        return List.copyOf(lootboxes.values());
    }

    public LootboxDef byId(final String id) {
        return id == null ? null : lootboxes.get(id.toLowerCase(Locale.ROOT));
    }

    public Set<String> ids() {
        return java.util.Set.copyOf(lootboxes.keySet());
    }
}
