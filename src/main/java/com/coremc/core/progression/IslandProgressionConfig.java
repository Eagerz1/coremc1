package com.coremc.core.progression;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Configuration for the connected island progression layer: island XP
 * sources and caps, the 1-30 island-level curve, limited Mastery Point
 * rewards, mastery branches and the Island Core module-slot milestones.
 *
 * <p>The config is intentionally data-heavy. Balance changes should live in
 * {@code progression.yml}, not in Java, so season pacing can be tuned without
 * rebuilding the plugin.</p>
 */
public final class IslandProgressionConfig {

    private static final String FILE_NAME = "progression.yml";

    /** One island level. {@code xp} is total XP required to be this level. */
    public record LevelDef(int level, long xp, int masteryPoints, long skyTokens, String milestone) {
    }

    /** One active gameplay source for Island XP. */
    public record SourceDef(String id, String name, Material icon, double xpPerUnit,
                            double softCapXp, double hardCapXp, double softMultiplier,
                            long windowMillis) {
        public boolean capped() {
            return hardCapXp > 0 && windowMillis > 0;
        }
    }

    /** A visible Island Mastery branch. */
    public record MasteryBranch(String id, String name, Material icon, List<MasteryUpgrade> upgrades) {
        public MasteryUpgrade upgrade(final String upgradeId) {
            for (final MasteryUpgrade upgrade : upgrades) {
                if (upgrade.id().equalsIgnoreCase(upgradeId)) {
                    return upgrade;
                }
            }
            return null;
        }
    }

    /** One purchaseable node in a branch. Nodes are intentionally one-time unlocks. */
    public record MasteryUpgrade(String branchId, String id, String name, Material icon,
                                 int islandLevel, int masteryPoints, double money,
                                 long skyTokens, List<String> requires, List<String> lore,
                                 Map<String, Double> effects) {
        public String key() {
            return branchId + "." + id;
        }

        public double effect(final String id) {
            return effects.getOrDefault(id, 0.0D);
        }
    }

    /** Configured Island Core module. Module items can be earned later by gameplay. */
    public record ModuleDef(String id, String name, Material icon, int islandLevel,
                            List<String> lore, Map<String, Double> effects) {
        public double effect(final String id) {
            return effects.getOrDefault(id, 0.0D);
        }
    }

    private final JavaPlugin plugin;
    private boolean enabled = true;
    private List<LevelDef> levels = List.of(new LevelDef(1, 0L, 0, 0L, "Starter Island"));
    private final Map<String, SourceDef> sources = new LinkedHashMap<>();
    private final Map<String, MasteryBranch> branches = new LinkedHashMap<>();
    private final Map<String, ModuleDef> modules = new LinkedHashMap<>();
    private List<Integer> moduleSlotLevels = List.of();
    private int baseGeneratorCapacity = 1;
    private double levelUpIslandTopPoints = 5000.0D;

    public IslandProgressionConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Extracts the default progression.yml on first run, then parses and validates it. */
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

    /** Parses and validates a YAML document (also used by tests). */
    void parse(final YamlConfiguration yaml) {
        final List<String> problems = new ArrayList<>();
        final List<LevelDef> parsedLevels = parseLevels(yaml, problems);
        final Map<String, SourceDef> parsedSources = parseSources(yaml, problems);
        final Map<String, MasteryBranch> parsedBranches = parseBranches(yaml, problems);
        final Map<String, ModuleDef> parsedModules = parseModules(yaml, problems);
        final List<Integer> parsedModuleSlots = parseModuleSlotLevels(yaml, problems);

        this.baseGeneratorCapacity = Math.max(0, yaml.getInt("industry.base-generator-capacity", 1));
        this.levelUpIslandTopPoints = Math.max(0.0D,
                yaml.getDouble("settings.level-up-island-top-points", 5000.0D));

        validateUpgradeRequires(parsedBranches, problems);

        if (!problems.isEmpty()) {
            enabled = false;
            throw new IllegalArgumentException("broken " + FILE_NAME + ": "
                    + String.join("; ", problems));
        }
        this.levels = List.copyOf(parsedLevels);
        this.sources.clear();
        this.sources.putAll(parsedSources);
        this.branches.clear();
        this.branches.putAll(parsedBranches);
        this.modules.clear();
        this.modules.putAll(parsedModules);
        this.moduleSlotLevels = List.copyOf(parsedModuleSlots);
        this.enabled = true;
    }

    private List<LevelDef> parseLevels(final YamlConfiguration yaml, final List<String> problems) {
        final List<LevelDef> parsed = new ArrayList<>();
        for (final Map<?, ?> raw : yaml.getMapList("levels")) {
            final int level = intValue(raw.get("level"), -1);
            final long xp = longValue(raw.get("xp"), -1L);
            final int points = intValue(raw.get("mastery-points"), 0);
            final long tokens = longValue(raw.get("sky-tokens"), 0L);
            final String milestone = stringValue(raw.get("milestone"), "");
            if (level < 1) {
                problems.add("levels: entry has invalid level " + level);
            }
            if (xp < 0) {
                problems.add("levels." + level + ": xp cannot be negative");
            }
            if (points < 0) {
                problems.add("levels." + level + ": mastery-points cannot be negative");
            }
            if (tokens < 0) {
                problems.add("levels." + level + ": sky-tokens cannot be negative");
            }
            parsed.add(new LevelDef(level, xp, points, tokens, milestone));
        }
        if (parsed.isEmpty()) {
            problems.add("missing 'levels' list");
            return parsed;
        }
        parsed.sort(java.util.Comparator.comparingInt(LevelDef::level));
        long previousXp = -1L;
        int expected = 1;
        for (final LevelDef level : parsed) {
            if (level.level() != expected) {
                problems.add("levels must be contiguous from 1 (expected " + expected
                        + ", found " + level.level() + ")");
            }
            if (level.xp() < previousXp) {
                problems.add("levels." + level.level() + ": xp must not go backwards");
            }
            previousXp = level.xp();
            expected++;
        }
        if (!parsed.isEmpty() && parsed.get(0).xp() != 0L) {
            problems.add("levels.1 must require xp 0");
        }
        return parsed;
    }

    private Map<String, SourceDef> parseSources(final YamlConfiguration yaml, final List<String> problems) {
        final Map<String, SourceDef> parsed = new LinkedHashMap<>();
        final ConfigurationSection root = yaml.getConfigurationSection("sources");
        if (root == null) {
            problems.add("missing 'sources' mapping");
            return parsed;
        }
        for (final String rawId : root.getKeys(false)) {
            final String id = normalise(rawId);
            final ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                problems.add("sources." + rawId + " is not a mapping");
                continue;
            }
            final Material icon = material(section.getString("icon"), "sources." + rawId, problems);
            final double xp = section.getDouble("xp-per-unit", -1.0D);
            if (xp <= 0) {
                problems.add("sources." + rawId + ": xp-per-unit must be positive");
            }
            final double soft = Math.max(0.0D, section.getDouble("soft-cap-xp", 0.0D));
            final double hard = Math.max(0.0D, section.getDouble("hard-cap-xp", 0.0D));
            final double multiplier = section.getDouble("soft-multiplier", 0.25D);
            if (multiplier < 0 || multiplier > 1) {
                problems.add("sources." + rawId + ": soft-multiplier must be between 0 and 1");
            }
            if (hard > 0 && soft > hard) {
                problems.add("sources." + rawId + ": soft-cap-xp cannot exceed hard-cap-xp");
            }
            final long windowMinutes = Math.max(0L, section.getLong("window-minutes", 60L));
            if (icon != null && xp > 0) {
                parsed.put(id, new SourceDef(id, section.getString("name", title(id)), icon, xp,
                        soft, hard, Math.max(0.0D, Math.min(1.0D, multiplier)),
                        windowMinutes * 60_000L));
            }
        }
        return parsed;
    }

    private Map<String, MasteryBranch> parseBranches(final YamlConfiguration yaml,
                                                     final List<String> problems) {
        final Map<String, MasteryBranch> parsed = new LinkedHashMap<>();
        final ConfigurationSection root = yaml.getConfigurationSection("branches");
        if (root == null) {
            problems.add("missing 'branches' mapping");
            return parsed;
        }
        for (final String rawId : root.getKeys(false)) {
            final String id = normalise(rawId);
            final ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                problems.add("branches." + rawId + " is not a mapping");
                continue;
            }
            final Material icon = material(section.getString("icon"), "branches." + rawId, problems);
            final List<MasteryUpgrade> upgrades = parseUpgrades(id,
                    section.getConfigurationSection("upgrades"), problems);
            if (icon != null && !upgrades.isEmpty()) {
                parsed.put(id, new MasteryBranch(id, section.getString("name", title(id)), icon,
                        List.copyOf(upgrades)));
            }
        }
        return parsed;
    }

    private List<MasteryUpgrade> parseUpgrades(final String branchId, final ConfigurationSection root,
                                               final List<String> problems) {
        final List<MasteryUpgrade> parsed = new ArrayList<>();
        if (root == null) {
            problems.add("branches." + branchId + ": missing upgrades mapping");
            return parsed;
        }
        for (final String rawId : root.getKeys(false)) {
            final String id = normalise(rawId);
            final ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                problems.add("branches." + branchId + "." + rawId + " is not a mapping");
                continue;
            }
            final Material icon = material(section.getString("icon"),
                    "branches." + branchId + ".upgrades." + rawId, problems);
            final int islandLevel = Math.max(1, section.getInt("island-level", 1));
            final int points = section.getInt("mastery-points", 1);
            if (points <= 0) {
                problems.add("branches." + branchId + ".upgrades." + rawId
                        + ": mastery-points must be positive");
            }
            final double money = section.getDouble("money", 0.0D);
            if (money < 0) {
                problems.add("branches." + branchId + ".upgrades." + rawId
                        + ": money cannot be negative");
            }
            final long tokens = section.getLong("sky-tokens", 0L);
            if (tokens < 0) {
                problems.add("branches." + branchId + ".upgrades." + rawId
                        + ": sky-tokens cannot be negative");
            }
            final List<String> requires = new ArrayList<>();
            for (final String rawRequire : section.getStringList("requires")) {
                requires.add(normaliseRequirement(branchId, rawRequire));
            }
            final List<String> lore = section.getStringList("lore");
            final Map<String, Double> effects = parseEffects(section.getConfigurationSection("effects"));
            if (icon != null && points > 0) {
                parsed.add(new MasteryUpgrade(branchId, id, section.getString("name", title(id)), icon,
                        islandLevel, points, Math.max(0.0D, money), Math.max(0L, tokens),
                        List.copyOf(requires), List.copyOf(lore), Map.copyOf(effects)));
            }
        }
        return parsed;
    }

    private Map<String, ModuleDef> parseModules(final YamlConfiguration yaml, final List<String> problems) {
        final Map<String, ModuleDef> parsed = new LinkedHashMap<>();
        final ConfigurationSection root = yaml.getConfigurationSection("core.modules");
        if (root == null) {
            return parsed;
        }
        for (final String rawId : root.getKeys(false)) {
            final String id = normalise(rawId);
            final ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                problems.add("core.modules." + rawId + " is not a mapping");
                continue;
            }
            final Material icon = material(section.getString("icon"), "core.modules." + rawId, problems);
            final int islandLevel = Math.max(1, section.getInt("island-level", 1));
            final List<String> lore = section.getStringList("lore");
            if (icon != null) {
                parsed.put(id, new ModuleDef(id, section.getString("name", title(id)), icon,
                        islandLevel, List.copyOf(lore), Map.copyOf(parseEffects(
                                section.getConfigurationSection("effects")))));
            }
        }
        return parsed;
    }

    private List<Integer> parseModuleSlotLevels(final YamlConfiguration yaml,
                                                final List<String> problems) {
        final List<Integer> parsed = new ArrayList<>();
        for (final Object raw : yaml.getList("core.module-slot-levels", List.of())) {
            final int level = intValue(raw, -1);
            if (level <= 0) {
                problems.add("core.module-slot-levels contains invalid level " + raw);
            } else {
                parsed.add(level);
            }
        }
        Collections.sort(parsed);
        return parsed;
    }

    private Map<String, Double> parseEffects(final ConfigurationSection section) {
        final Map<String, Double> parsed = new LinkedHashMap<>();
        if (section == null) {
            return parsed;
        }
        for (final String rawKey : section.getKeys(false)) {
            parsed.put(normalise(rawKey), section.getDouble(rawKey, 0.0D));
        }
        return parsed;
    }

    private void validateUpgradeRequires(final Map<String, MasteryBranch> parsedBranches,
                                         final List<String> problems) {
        final java.util.Set<String> keys = new java.util.HashSet<>();
        for (final MasteryBranch branch : parsedBranches.values()) {
            for (final MasteryUpgrade upgrade : branch.upgrades()) {
                keys.add(upgrade.key());
            }
        }
        for (final MasteryBranch branch : parsedBranches.values()) {
            for (final MasteryUpgrade upgrade : branch.upgrades()) {
                for (final String required : upgrade.requires()) {
                    if (!keys.contains(required)) {
                        problems.add("branches." + branch.id() + ".upgrades." + upgrade.id()
                                + ": unknown requirement '" + required + "'");
                    }
                }
            }
        }
    }

    private Material material(final String key, final String where, final List<String> problems) {
        final Material material = Material.matchMaterial(key == null ? "" : key);
        if (material == null) {
            problems.add(where + ": unknown material '" + key + "'");
        }
        return material;
    }

    private String normaliseRequirement(final String currentBranch, final String raw) {
        final String value = normalise(raw);
        return value.contains(".") ? value : currentBranch + "." + value;
    }

    private static String normalise(final String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
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

    private static String stringValue(final Object raw, final String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static int intValue(final Object raw, final int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw));
        } catch (final NumberFormatException exception) {
            return fallback;
        }
    }

    private static long longValue(final Object raw, final long fallback) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return raw == null ? fallback : Long.parseLong(String.valueOf(raw));
        } catch (final NumberFormatException exception) {
            return fallback;
        }
    }

    /** Disabled fallback used when progression.yml is broken. */
    public static IslandProgressionConfig disabled() {
        final IslandProgressionConfig config = new IslandProgressionConfig(null);
        config.enabled = false;
        config.levels = List.of(new LevelDef(1, 0L, 0, 0L, "Starter Island"));
        config.sources.clear();
        config.branches.clear();
        config.modules.clear();
        config.moduleSlotLevels = List.of();
        config.baseGeneratorCapacity = 0;
        return config;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<LevelDef> levels() {
        return levels;
    }

    public int maxLevel() {
        return levels.get(levels.size() - 1).level();
    }

    public LevelDef levelDef(final int level) {
        if (level < 1 || level > maxLevel()) {
            return null;
        }
        return levels.get(level - 1);
    }

    /** Highest level reached by total island XP. */
    public int levelForXp(final long xp) {
        int result = 1;
        for (final LevelDef def : levels) {
            if (xp >= def.xp()) {
                result = def.level();
            } else {
                break;
            }
        }
        return result;
    }

    public long xpForLevel(final int level) {
        final LevelDef def = levelDef(Math.max(1, Math.min(maxLevel(), level)));
        return def == null ? 0L : def.xp();
    }

    public LevelDef nextLevel(final int level) {
        return level >= maxLevel() ? null : levelDef(level + 1);
    }

    /** Total Mastery Points earned up to and including {@code level}. */
    public int totalMasteryPoints(final int level) {
        int total = 0;
        for (final LevelDef def : levels) {
            if (def.level() <= level) {
                total += def.masteryPoints();
            }
        }
        return total;
    }

    /** Sum of sky tokens granted for reaching levels {@code fromExclusive+1..toInclusive}. */
    public long skyTokensBetween(final int fromExclusive, final int toInclusive) {
        long total = 0L;
        for (final LevelDef def : levels) {
            if (def.level() > fromExclusive && def.level() <= toInclusive) {
                total += def.skyTokens();
            }
        }
        return total;
    }

    public SourceDef source(final String id) {
        return sources.get(normalise(id));
    }

    public List<SourceDef> sources() {
        return List.copyOf(sources.values());
    }

    public MasteryBranch branch(final String id) {
        return branches.get(normalise(id));
    }

    public List<MasteryBranch> branches() {
        return List.copyOf(branches.values());
    }

    public MasteryUpgrade upgrade(final String branchId, final String upgradeId) {
        final MasteryBranch branch = branch(branchId);
        return branch == null ? null : branch.upgrade(normalise(upgradeId));
    }

    public List<ModuleDef> modules() {
        return List.copyOf(modules.values());
    }

    public ModuleDef module(final String id) {
        return modules.get(normalise(id));
    }

    public int moduleSlots(final int islandLevel) {
        int slots = 0;
        for (final int unlockLevel : moduleSlotLevels) {
            if (islandLevel >= unlockLevel) {
                slots++;
            }
        }
        return slots;
    }

    public List<Integer> moduleSlotLevels() {
        return moduleSlotLevels;
    }

    public int baseGeneratorCapacity() {
        return baseGeneratorCapacity;
    }

    public double levelUpIslandTopPoints() {
        return levelUpIslandTopPoints;
    }
}
