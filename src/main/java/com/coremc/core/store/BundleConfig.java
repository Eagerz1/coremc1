package com.coremc.core.store;

import com.coremc.core.crate.CrateConfig;
import com.coremc.core.lootbox.LootboxConfig;
import com.coremc.core.reward.RewardType;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and validates {@code bundles.yml}. Every bundle content line
 * is cross-checked against the configured keys and lootboxes, so a
 * bundle can never sell something that does not exist — invalid
 * bundles fail the load loudly instead of corrupting purchases.
 *
 * <p>Content shape: {@code "key:river:2"} / {@code "lootbox:core:1"}.</p>
 */
public final class BundleConfig {

    private static final String FILE_NAME = "bundles.yml";

    private final JavaPlugin plugin;
    private final Map<String, BundleDef> bundles = new LinkedHashMap<>();

    public BundleConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Extracts the default bundles.yml on first run, then parses and validates. */
    public void load(final CrateConfig crates, final LootboxConfig lootboxes) {
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
        parse(yaml, crates, lootboxes);
    }

    /** Parses and validates a YAML document (also used by the tests). */
    public void parse(final YamlConfiguration yaml, final CrateConfig crates,
                      final LootboxConfig lootboxes) {
        final List<String> problems = new ArrayList<>();
        bundles.clear();

        final ConfigurationSection section = yaml.getConfigurationSection("bundles");
        if (section == null) {
            throw new IllegalArgumentException("broken " + FILE_NAME
                    + ": missing bundles section");
        }
        for (final String rawId : section.getKeys(false)) {
            parseBundle(section, rawId, crates, lootboxes, problems);
        }
        if (bundles.isEmpty()) {
            problems.add("no valid bundles configured");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("broken " + FILE_NAME + ": "
                    + String.join("; ", problems));
        }
    }

    private void parseBundle(final ConfigurationSection section, final String rawId,
                             final CrateConfig crates, final LootboxConfig lootboxes,
                             final List<String> problems) {
        final String id = rawId.toLowerCase(Locale.ROOT);
        final ConfigurationSection entry = section.getConfigurationSection(rawId);
        if (entry == null) {
            problems.add("bundles." + rawId + ": not a section");
            return;
        }
        final String name = entry.getString("name", "");
        if (name.isBlank()) {
            problems.add("bundles." + rawId + ": missing name");
            return;
        }
        final Material icon = Material.matchMaterial(entry.getString("icon", "CHEST"));
        if (icon == null || icon == Material.AIR) {
            problems.add("bundles." + rawId + ": unknown icon material");
            return;
        }
        final long price = entry.getLong("price", -1);
        if (price < 0) {
            problems.add("bundles." + rawId + ": price must be >= 0 Credits");
            return;
        }
        final List<BundleDef.Content> contents = new ArrayList<>();
        for (final String line : entry.getStringList("contents")) {
            final BundleDef.Content content =
                    parseContent(line, rawId, crates, lootboxes, problems);
            if (content != null) {
                contents.add(content);
            }
        }
        if (contents.isEmpty()) {
            problems.add("bundles." + rawId + ": no valid contents");
            return;
        }
        if (bundles.containsKey(id)) {
            problems.add("bundles." + rawId + ": duplicate bundle id");
            return;
        }
        bundles.put(id, new BundleDef(id, name, icon, price, contents));
    }

    private static BundleDef.Content parseContent(final String line, final String bundleId,
                                                  final CrateConfig crates,
                                                  final LootboxConfig lootboxes,
                                                  final List<String> problems) {
        final String[] parts = line == null ? new String[0] : line.trim().split(":");
        if (parts.length != 3) {
            problems.add("bundles." + bundleId + ": bad content line '" + line
                    + "' (want kind:id:amount)");
            return null;
        }
        final String kind = parts[0].toLowerCase(Locale.ROOT);
        final String refId = parts[1].toLowerCase(Locale.ROOT);
        final int amount;
        try {
            amount = Integer.parseInt(parts[2]);
        } catch (final NumberFormatException exception) {
            problems.add("bundles." + bundleId + ": bad amount in '" + line + "'");
            return null;
        }
        if (amount <= 0) {
            problems.add("bundles." + bundleId + ": amount must be > 0 in '" + line + "'");
            return null;
        }
        if ("key".equals(kind)) {
            if (crates != null && crates.key(refId) == null) {
                problems.add("bundles." + bundleId + ": unknown key '" + refId + "'");
                return null;
            }
            final String display = crates == null || crates.key(refId) == null
                    ? refId : crates.key(refId).name();
            return new BundleDef.Content(RewardType.KEY, refId, amount, display);
        }
        if ("lootbox".equals(kind)) {
            if (lootboxes != null && lootboxes.byId(refId) == null) {
                problems.add("bundles." + bundleId + ": unknown lootbox '" + refId + "'");
                return null;
            }
            final String display = lootboxes == null || lootboxes.byId(refId) == null
                    ? refId : lootboxes.byId(refId).name();
            return new BundleDef.Content(RewardType.LOOTBOX, refId, amount, display);
        }
        problems.add("bundles." + bundleId + ": bundles may only contain keys and "
                + "lootboxes — not '" + kind + "'");
        return null;
    }

    // ------------------------------------------------------------------
    // access
    // ------------------------------------------------------------------

    public List<BundleDef> all() {
        return List.copyOf(bundles.values());
    }

    public BundleDef byId(final String id) {
        return id == null ? null : bundles.get(id.toLowerCase(Locale.ROOT));
    }
}
