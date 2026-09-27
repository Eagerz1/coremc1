package com.coremc.core.progression;

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
 * Config for persistent Island Core buffs. Buffs are unlocked over time but
 * equipped through the existing Island Core module slots (not a second slot
 * system). Numbers live in island-buffs.yml so the season can be tuned without
 * Java changes.
 */
public final class IslandCoreBuffConfig {

    public static final List<String> REQUIRED_BUFFS = List.of(
            "rich-veins", "slayer-frenzy", "deep-waters", "generator-overdrive",
            "core-discovery", "role-synergy", "momentum", "fortune-cycle", "core-surge");

    private static final String FILE_NAME = "island-buffs.yml";

    public record WeightedReward(Material material, double weight, int min, int max, String discovery) {
    }

    public record BuffDef(String id, String name, Material icon, int islandLevel, double money,
                          long skyTokens, List<String> masteryRequires, List<String> lore,
                          Map<String, Double> numbers, Map<String, String> strings,
                          Map<String, List<String>> lists, List<WeightedReward> rewards) {
        public double number(final String key, final double fallback) {
            return numbers.getOrDefault(normalise(key), fallback);
        }

        public String string(final String key, final String fallback) {
            return strings.getOrDefault(normalise(key), fallback);
        }

        public List<String> list(final String key) {
            return lists.getOrDefault(normalise(key), List.of());
        }
    }

    public record ClampConfig(double killProgressionMax, double omniToolOutputMax,
                              double discoveryMax, double generatorSpeedMax,
                              double islandXpMax, double roleXpMax,
                              double omniToolProgressMax, double specialFrequencyMax) {
    }

    private final JavaPlugin plugin;
    private boolean enabled = true;
    private long swapCooldownMillis = 12L * 60L * 60L * 1000L;
    private long fortuneCooldownMillis = 24L * 60L * 60L * 1000L;
    private double momentumMax = 100.0D;
    private ClampConfig clamps = new ClampConfig(2.0, 2.0, 3.0, 3.0, 2.0, 2.0, 2.0, 3.0);
    private final Map<String, BuffDef> buffs = new LinkedHashMap<>();

    public IslandCoreBuffConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

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

    void parse(final YamlConfiguration yaml) {
        final List<String> problems = new ArrayList<>();
        this.swapCooldownMillis = Math.max(0L,
                yaml.getLong("settings.swap-cooldown-hours", 12L)) * 60L * 60L * 1000L;
        this.fortuneCooldownMillis = Math.max(0L,
                yaml.getLong("settings.fortune-cycle.cooldown-hours", 24L)) * 60L * 60L * 1000L;
        this.momentumMax = Math.max(1.0D, yaml.getDouble("settings.momentum.max", 100.0D));
        this.clamps = parseClamps(yaml, problems);

        final Map<String, BuffDef> parsed = new LinkedHashMap<>();
        final ConfigurationSection root = yaml.getConfigurationSection("buffs");
        if (root == null) {
            problems.add("missing 'buffs' mapping");
        } else {
            for (final String rawId : root.getKeys(false)) {
                final ConfigurationSection section = root.getConfigurationSection(rawId);
                if (section == null) {
                    problems.add("buffs." + rawId + " is not a mapping");
                    continue;
                }
                final BuffDef def = parseBuff(normalise(rawId), section, problems);
                if (def != null) {
                    parsed.put(def.id(), def);
                }
            }
        }
        for (final String required : REQUIRED_BUFFS) {
            if (!parsed.containsKey(required)) {
                problems.add("missing required buff '" + required + "'");
            }
        }
        if (!problems.isEmpty()) {
            enabled = false;
            throw new IllegalArgumentException("broken " + FILE_NAME + ": "
                    + String.join("; ", problems));
        }
        buffs.clear();
        buffs.putAll(parsed);
        enabled = true;
    }

    private ClampConfig parseClamps(final YamlConfiguration yaml, final List<String> problems) {
        final ConfigurationSection section = yaml.getConfigurationSection("settings.clamps");
        final double kill = positive(section, "kill-progression-max", 2.0, problems);
        final double output = positive(section, "omnitool-output-max", 2.0, problems);
        final double discovery = positive(section, "discovery-max", 3.0, problems);
        final double generator = positive(section, "generator-speed-max", 3.0, problems);
        final double islandXp = positive(section, "island-xp-max", 2.0, problems);
        final double roleXp = positive(section, "role-xp-max", 2.0, problems);
        final double omniProgress = positive(section, "omnitool-progress-max", 2.0, problems);
        final double frequency = positive(section, "special-frequency-max", 3.0, problems);
        return new ClampConfig(kill, output, discovery, generator, islandXp, roleXp, omniProgress, frequency);
    }

    private double positive(final ConfigurationSection section, final String key, final double fallback,
                            final List<String> problems) {
        final double value = section == null ? fallback : section.getDouble(key, fallback);
        if (value < 1.0D) {
            problems.add("settings.clamps." + key + " must be at least 1");
            return fallback;
        }
        return value;
    }

    private BuffDef parseBuff(final String id, final ConfigurationSection section,
                              final List<String> problems) {
        final Material icon = Material.matchMaterial(section.getString("icon", ""));
        if (icon == null) {
            problems.add("buffs." + id + ": unknown icon '" + section.getString("icon") + "'");
            return null;
        }
        final int islandLevel = Math.max(1, section.getInt("unlock.island-level", 1));
        final double money = section.getDouble("unlock.money", 0.0D);
        if (money < 0.0D) {
            problems.add("buffs." + id + ": unlock.money cannot be negative");
        }
        final long skyTokens = section.getLong("unlock.sky-tokens", 0L);
        if (skyTokens < 0L) {
            problems.add("buffs." + id + ": unlock.sky-tokens cannot be negative");
        }
        final List<String> mastery = new ArrayList<>();
        for (final String raw : section.getStringList("unlock.mastery")) {
            mastery.add(normalise(raw).replace(' ', '.'));
        }
        final Map<String, Double> numbers = new LinkedHashMap<>();
        final Map<String, String> strings = new LinkedHashMap<>();
        final Map<String, List<String>> lists = new LinkedHashMap<>();
        collectSettings(section.getConfigurationSection("settings"), "", numbers, strings, lists);
        return new BuffDef(id, section.getString("name", title(id)), icon, islandLevel,
                Math.max(0.0D, money), Math.max(0L, skyTokens), List.copyOf(mastery),
                section.getStringList("lore"), Map.copyOf(numbers), Map.copyOf(strings),
                Map.copyOf(lists), List.copyOf(parseRewards(id, section.getConfigurationSection("rewards"), problems)));
    }

    private void collectSettings(final ConfigurationSection section, final String prefix,
                                 final Map<String, Double> numbers, final Map<String, String> strings,
                                 final Map<String, List<String>> lists) {
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(false)) {
            final String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                collectSettings(section.getConfigurationSection(key), path, numbers, strings, lists);
            } else if (section.isList(key)) {
                lists.put(normalise(path), section.getStringList(key));
            } else if (section.isDouble(key) || section.isInt(key) || section.isLong(key)) {
                numbers.put(normalise(path), section.getDouble(key));
            } else if (section.isString(key)) {
                strings.put(normalise(path), section.getString(key, ""));
            }
        }
    }

    private List<WeightedReward> parseRewards(final String id, final ConfigurationSection section,
                                              final List<String> problems) {
        final List<WeightedReward> rewards = new ArrayList<>();
        if (section == null) {
            return rewards;
        }
        for (final Map<?, ?> raw : section.getMapList("table")) {
            final Object rawMaterial = raw.get("material");
            final Object rawDiscovery = raw.get("discovery");
            final Material material = Material.matchMaterial(rawMaterial == null ? "" : String.valueOf(rawMaterial));
            final String discovery = rawDiscovery == null ? "" : String.valueOf(rawDiscovery);
            if (material == null && discovery.isBlank()) {
                problems.add("buffs." + id + ".rewards.table has entry without material/discovery: " + raw);
                continue;
            }
            final double weight = doubleOf(raw.get("weight"), 1.0D);
            if (weight <= 0.0D) {
                problems.add("buffs." + id + ".rewards.table weight must be positive");
                continue;
            }
            final int min = Math.max(1, (int) doubleOf(raw.get("min"), 1.0D));
            final int max = Math.max(min, (int) doubleOf(raw.get("max"), min));
            rewards.add(new WeightedReward(material, weight, min, max, discovery));
        }
        return rewards;
    }

    private static double doubleOf(final Object raw, final double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return raw == null ? fallback : Double.parseDouble(String.valueOf(raw));
        } catch (final NumberFormatException exception) {
            return fallback;
        }
    }

    public static IslandCoreBuffConfig disabled() {
        final IslandCoreBuffConfig config = new IslandCoreBuffConfig(null);
        config.enabled = false;
        config.buffs.clear();
        return config;
    }

    public boolean enabled() {
        return enabled;
    }

    public long swapCooldownMillis() {
        return swapCooldownMillis;
    }

    public long fortuneCooldownMillis() {
        return fortuneCooldownMillis;
    }

    public double momentumMax() {
        return momentumMax;
    }

    public ClampConfig clamps() {
        return clamps;
    }

    public List<BuffDef> buffs() {
        return List.copyOf(buffs.values());
    }

    public BuffDef buff(final String id) {
        return buffs.get(normalise(id));
    }

    static String normalise(final String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String title(final String id) {
        final String[] parts = id.replace('-', ' ').split(" ");
        final StringBuilder result = new StringBuilder();
        for (final String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }
}
