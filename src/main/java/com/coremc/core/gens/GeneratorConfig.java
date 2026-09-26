package com.coremc.core.gens;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and validates {@code generators.yml}: the generator
 * progression (names, blocks, prices, output, intervals, island
 * requirements, per-island caps and the upgrade path) plus the global
 * generator settings.
 *
 * <p>Like the shop and the spawner config, every problem in the file
 * is collected into one loud exception so a misconfiguration can
 * never half-work; the plugin then disables the /gens system with a
 * single log line instead of breaking.</p>
 */
public final class GeneratorConfig {

    private static final String FILE_NAME = "generators.yml";

    private final JavaPlugin plugin;
    private final Map<String, GeneratorTier> generators = new LinkedHashMap<>();

    private boolean enabled = true;
    private int maxStack = 64;
    private int maxPerIsland = 64;
    private boolean requireOnline = true;
    private boolean produceItems = true;
    private boolean holograms = true;

    public GeneratorConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** A disabled config: no generators, menus say so instead of crashing. */
    public static GeneratorConfig disabled() {
        final GeneratorConfig config = new GeneratorConfig(null);
        config.enabled = false;
        return config;
    }

    /** Extracts the default generators.yml on first run, then parses and validates. */
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
        parse(yaml);
    }

    /** Parses and validates a YAML document (also used by the tests). */
    void parse(final YamlConfiguration yaml) {
        final List<String> problems = new ArrayList<>();
        generators.clear();

        parseSettings(yaml);
        parseGenerators(yaml, problems);
        validateUpgradePath(problems);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("broken " + FILE_NAME + ": "
                    + String.join("; ", problems));
        }
        this.enabled = true;
    }

    private void parseSettings(final YamlConfiguration yaml) {
        this.maxStack = clamp(yaml.getInt("settings.max-stack", 64), 1, 1000);
        this.maxPerIsland = clamp(yaml.getInt("settings.max-per-island", 64), 1, 10_000);
        this.requireOnline = yaml.getBoolean("settings.require-online", true);
        this.produceItems = yaml.getBoolean("settings.produce-items", true);
        this.holograms = yaml.getBoolean("settings.holograms", true);
    }

    private void parseGenerators(final YamlConfiguration yaml, final List<String> problems) {
        final ConfigurationSection root = yaml.getConfigurationSection("generators");
        if (root == null) {
            problems.add("missing 'generators' mapping");
            return;
        }
        final Set<Integer> tiers = new HashSet<>();
        final List<GeneratorTier> parsed = new ArrayList<>();
        for (final String id : root.getKeys(false)) {
            final ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                problems.add("generators." + id + " is not a section");
                continue;
            }
            final GeneratorTier tier = parseGenerator(id.toLowerCase(Locale.ROOT), section, problems);
            if (tier == null) {
                continue;
            }
            if (!tiers.add(tier.tier())) {
                problems.add("generators." + id + ": tier " + tier.tier() + " is used twice");
            }
            parsed.add(tier);
        }
        if (parsed.isEmpty()) {
            problems.add("'generators' has no usable entries");
        }
        parsed.sort(Comparator.comparingInt(GeneratorTier::tier));
        for (final GeneratorTier tier : parsed) {
            generators.put(tier.id(), tier);
        }
    }

    private GeneratorTier parseGenerator(final String id, final ConfigurationSection section,
                                         final List<String> problems) {
        final String where = "generators." + id;
        final String name = section.getString("name", "");
        if (name.isBlank()) {
            problems.add(where + ": missing 'name'");
            return null;
        }
        final int tier = section.getInt("tier", 0);
        if (tier < 1) {
            problems.add(where + ": 'tier' must be 1 or higher");
            return null;
        }
        final Material block = material(section.getString("block"), where + ".block", problems);
        if (block == null) {
            return null;
        }
        if (Boolean.FALSE.equals(isBlock(block))) {
            problems.add(where + ".block: " + block + " is not a placeable block");
            return null;
        }
        final Material icon = section.isString("icon")
                ? material(section.getString("icon"), where + ".icon", problems) : block;

        final double price = section.getDouble("price", -1);
        if (price < 0) {
            problems.add(where + ": 'price' must be zero or more");
            return null;
        }
        final int interval = section.getInt("interval", 0);
        if (interval < 1) {
            problems.add(where + ": 'interval' (seconds) must be 1 or more");
            return null;
        }
        final double value = section.getDouble("value", -1);
        if (value < 0) {
            problems.add(where + ": 'value' must be zero or more");
            return null;
        }

        Material output = null;
        int outputAmount = 0;
        if (section.isConfigurationSection("output")) {
            final ConfigurationSection out = section.getConfigurationSection("output");
            output = material(out.getString("material"), where + ".output.material", problems);
            outputAmount = Math.max(1, out.getInt("amount", 1));
            if (output != null && Boolean.FALSE.equals(isItem(output))) {
                problems.add(where + ".output.material: " + output + " is not an item");
                output = null;
            }
        }

        final double requiredPoints = Math.max(0, section.getDouble("required-island-points", 0));
        final int maxPlaced = clamp(section.getInt("max-placed", 16), 1, 10_000);
        final String color = colorCode(section.getString("color", "&f"), where, problems);

        String upgradeTo = section.getString("upgrade.to");
        if (upgradeTo != null) {
            upgradeTo = upgradeTo.trim().toLowerCase(Locale.ROOT);
            if (upgradeTo.isEmpty()) {
                upgradeTo = null;
            }
        }
        double upgradeCost = section.getDouble("upgrade.cost", -1);
        if (upgradeTo != null && upgradeCost < 0) {
            problems.add(where + ".upgrade: 'cost' must be zero or more");
            upgradeCost = 0;
        }
        if (upgradeTo == null) {
            upgradeCost = 0;
        }
        if (upgradeTo != null && upgradeTo.equals(id)) {
            problems.add(where + ".upgrade.to: a generator cannot upgrade into itself");
            upgradeTo = null;
        }

        return new GeneratorTier(id, name, tier, color, block, icon == null ? block : icon,
                price, interval * 20, value, output, outputAmount, requiredPoints, maxPlaced,
                upgradeTo, upgradeCost);
    }

    /** Every {@code upgrade.to} must point at a real, higher generator. */
    private void validateUpgradePath(final List<String> problems) {
        for (final GeneratorTier tier : generators.values()) {
            if (!tier.hasUpgrade()) {
                continue;
            }
            final GeneratorTier next = generators.get(tier.upgradeTo());
            if (next == null) {
                problems.add("generators." + tier.id() + ".upgrade.to: unknown generator '"
                        + tier.upgradeTo() + "'");
            } else if (next.tier() <= tier.tier()) {
                problems.add("generators." + tier.id() + ".upgrade.to: '" + next.id()
                        + "' is not a higher tier");
            }
        }
    }

    private Material material(final String raw, final String where, final List<String> problems) {
        if (raw == null || raw.isBlank()) {
            problems.add(where + ": missing material");
            return null;
        }
        final Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            problems.add(where + ": '" + raw + "' is not a Minecraft material");
        }
        return material;
    }

    private String colorCode(final String raw, final String where, final List<String> problems) {
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '&') {
            problems.add(where + ".color: '" + raw + "' is not a & colour code");
            return "&f";
        }
        return raw.substring(0, 2);
    }

    /**
     * {@code material.isBlock()} — or null when the Bukkit registry is
     * not available (unit tests parse configs without a server, and a
     * config file must never fail to parse just because of that).
     */
    private static Boolean isBlock(final Material material) {
        try {
            return material.isBlock();
        } catch (final Throwable registryUnavailable) {
            return null;
        }
    }

    /** {@code material.isItem()}, registry-safe (see {@link #isBlock}). */
    private static Boolean isItem(final Material material) {
        try {
            return material.isItem();
        } catch (final Throwable registryUnavailable) {
            return null;
        }
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** Whether the generator system loaded successfully. */
    public boolean enabled() {
        return enabled && !generators.isEmpty();
    }

    /** Every generator, ordered by tier. */
    public List<GeneratorTier> all() {
        return List.copyOf(generators.values());
    }

    /** One generator by config id, or null. */
    public GeneratorTier byId(final String id) {
        return id == null ? null : generators.get(id.toLowerCase(Locale.ROOT));
    }

    /** The generator a tier upgrades into, or null at the top of the ladder. */
    public GeneratorTier next(final GeneratorTier tier) {
        return tier == null || !tier.hasUpgrade() ? null : generators.get(tier.upgradeTo());
    }

    /** How many generators may share one block. */
    public int maxStack() {
        return maxStack;
    }

    /** How many generators one island may have placed in total. */
    public int maxPerIsland() {
        return maxPerIsland;
    }

    /** Whether generators only run while an island member is online. */
    public boolean requireOnline() {
        return requireOnline;
    }

    /** Whether generators also push physical items into an adjacent container. */
    public boolean produceItems() {
        return produceItems;
    }

    /** Whether placed generators carry a floating label. */
    public boolean holograms() {
        return holograms;
    }
}
