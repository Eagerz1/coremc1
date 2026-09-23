package com.coremc.core.spawner;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and validates spawners.yml: groups, essences, relics, unique
 * drops, variant behaviour, upgrade costs and island-luck maths.
 * Like the shop, every problem is collected into one loud exception
 * so a misconfiguration can never half-work.
 */
public final class SpawnerConfig {

    private static final String FILE_NAME = "spawners.yml";

    private final JavaPlugin plugin;
    private final List<SpawnerGroup> groups = new ArrayList<>();
    private final Map<SpawnerVariant, SpawnerVariantSettings> variants =
            new EnumMap<>(SpawnerVariant.class);
    private final Map<SpawnerVariant, SpawnerUpgradeCost> upgradeDefaults =
            new EnumMap<>(SpawnerVariant.class);

    private double essenceChance = 0.25;
    private double relicChance = 0.01;
    private boolean announceEssence = false;
    private double luckBaseChance = 0.1;
    private double luckBonusPerLevel = 0.1;
    private int luckMaxLevel = 4;
    private List<Double> luckCosts = List.of(750.0, 2500.0, 7500.0, 20000.0);
    private int minDelayTicks = 100;
    private int maxDelayTicks = 400;
    private int playerRange = 16;
    private int maxSpawnerStack = 64;
    private boolean mobStackEnabled = true;
    private int mobStackRadius = 5;
    private int mobStackMax = 1024;

    public SpawnerConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Extracts the default spawners.yml on first run, then parses and validates. */
    public void load() {
        final File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (final Exception exception) {
            throw new IllegalArgumentException("cannot read " + FILE_NAME + ": " + exception.getMessage(),
                    exception);
        }
        parse(yaml);
    }

    /** Parses and validates a YAML document (also used by tests). */
    void parse(final YamlConfiguration yaml) {
        final List<String> problems = new ArrayList<>();

        parseSettings(yaml, problems);
        parseVariants(yaml, problems);
        parseUpgradeDefaults(yaml, problems);
        parseGroups(yaml, problems);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("broken " + FILE_NAME + ": " + String.join("; ", problems));
        }
    }

    private void parseSettings(final YamlConfiguration yaml, final List<String> problems) {
        this.essenceChance = chance(yaml, "settings.essence-chance", 0.25, problems);
        this.relicChance = chance(yaml, "settings.relic-chance", 0.01, problems);
        this.announceEssence = yaml.getBoolean("settings.announce-essence", false);
        this.luckBaseChance = chance(yaml, "settings.luck.base-chance", 0.1, problems);
        this.luckBonusPerLevel = chance(yaml, "settings.luck.bonus-per-level", 0.1, problems);

        this.luckMaxLevel = yaml.getInt("settings.luck.max-level", 4);
        if (luckMaxLevel < 0 || luckMaxLevel > 100) {
            problems.add("settings.luck.max-level " + luckMaxLevel + " is out of range");
            this.luckMaxLevel = 4;
        }
        final List<Double> costs = new ArrayList<>();
        for (final Object raw : yaml.getList("settings.luck.costs", List.of())) {
            try {
                costs.add(Math.max(0, Double.parseDouble(String.valueOf(raw))));
            } catch (final NumberFormatException exception) {
                problems.add("settings.luck.costs contains a non-number: " + raw);
            }
        }
        if (!costs.isEmpty() && costs.size() < luckMaxLevel) {
            problems.add("settings.luck.costs needs " + luckMaxLevel + " entries for max-level "
                    + luckMaxLevel + " (found " + costs.size() + ")");
        }
        this.luckCosts = costs.isEmpty() ? List.of(750.0, 2500.0, 7500.0, 20000.0) : List.copyOf(costs);

        this.minDelayTicks = Math.max(20, yaml.getInt("settings.spawner.min-delay-ticks", 100));
        this.maxDelayTicks = Math.max(minDelayTicks + 1,
                yaml.getInt("settings.spawner.max-delay-ticks", 400));
        this.playerRange = Math.max(1, yaml.getInt("settings.spawner.player-range", 16));
        this.maxSpawnerStack = Math.max(1, yaml.getInt("settings.spawner.max-stack", 64));
        this.mobStackEnabled = yaml.getBoolean("settings.mob-stack.enabled", true);
        this.mobStackRadius = Math.max(2, yaml.getInt("settings.mob-stack.radius", 5));
        this.mobStackMax = Math.max(2, yaml.getInt("settings.mob-stack.max", 1024));
    }

    /**
     * Parses the global {@code upgrade-defaults} section: the variant
     * progression every mob falls back to (group overrides and single-mob
     * {@code upgrades:} entries can beat it). Optional — without it every
     * mob must define its own complete upgrade costs.
     */
    private void parseUpgradeDefaults(final YamlConfiguration yaml, final List<String> problems) {
        upgradeDefaults.clear();
        final ConfigurationSection root = yaml.getConfigurationSection("upgrade-defaults");
        if (root == null) {
            return;
        }
        upgradeDefaults.putAll(parseCosts(root, "upgrade-defaults", problems));
        for (final SpawnerVariant variant : SpawnerVariant.values()) {
            if (variant != SpawnerVariant.NORMAL && !upgradeDefaults.containsKey(variant)) {
                problems.add("upgrade-defaults: missing cost for " + variant.name().toLowerCase());
            }
        }
    }

    /**
     * Parses a variant → cost mapping ({@code essence} from the mob's
     * group, {@code drops} of the mob's own unique drop, optional
     * {@code relics}). Unknown keys are reported loudly and skipped;
     * negative amounts clamp to zero.
     */
    private Map<SpawnerVariant, SpawnerUpgradeCost> parseCosts(final ConfigurationSection root,
                                                               final String where,
                                                               final List<String> problems) {
        final Map<SpawnerVariant, SpawnerUpgradeCost> costs = new EnumMap<>(SpawnerVariant.class);
        if (root == null) {
            return costs;
        }
        for (final String key : root.getKeys(false)) {
            final SpawnerVariant variant = SpawnerVariant.of(key);
            if (variant == null || variant == SpawnerVariant.NORMAL) {
                problems.add(where + ": '" + key + "' is not a variant above normal");
                continue;
            }
            final ConfigurationSection cost = root.getConfigurationSection(key);
            if (cost == null) {
                problems.add(where + ": '" + key + "' is not a mapping");
                continue;
            }
            costs.put(variant, new SpawnerUpgradeCost(
                    Math.max(0, cost.getInt("essence", 0)),
                    Math.max(0, cost.getInt("drops", 0)),
                    Math.max(0, cost.getInt("relics", 0))));
        }
        return costs;
    }

    private void parseVariants(final YamlConfiguration yaml, final List<String> problems) {


        final ConfigurationSection root = yaml.getConfigurationSection("variants");
        if (root == null) {
            problems.add("missing 'variants' mapping");
            return;
        }
        for (final SpawnerVariant variant : SpawnerVariant.values()) {
            final ConfigurationSection section = root.getConfigurationSection(variant.name().toLowerCase());
            if (section == null) {
                problems.add("variants." + variant.name().toLowerCase() + " is missing");
                continue;
            }
            final double rate = section.getDouble("rate", 1.0);
            if (rate <= 0) {
                problems.add("variants." + variant.name().toLowerCase() + ".rate must be positive");
                continue;
            }
            variants.put(variant, new SpawnerVariantSettings(
                    rate,
                    Math.max(1, section.getInt("count", 1)),
                    Math.max(1, section.getInt("nearby-limit", 6)),
                    section.getBoolean("auto-kill", false)));
        }
        if (variants.size() != SpawnerVariant.values().length) {
            problems.add("variants must define all of normal/advanced/ancient/mythic");
        }
    }

    private void parseGroups(final YamlConfiguration yaml, final List<String> problems) {
        final ConfigurationSection root = yaml.getConfigurationSection("groups");
        if (root == null) {
            problems.add("missing 'groups' mapping");
            return;
        }
        final List<SpawnerGroup> parsed = new ArrayList<>();
        for (final String groupId : root.getKeys(false)) {
            final ConfigurationSection section = root.getConfigurationSection(groupId);
            if (section == null) {
                problems.add("group '" + groupId + "' is not a mapping");
                continue;
            }
            final Material essence = material(section.getString("essence.item"), "group '" + groupId + "' essence", problems);
            final String essenceName = section.getString("essence.name", groupId + " Essence");
            final Material relic = material(section.getString("relic.item"), "group '" + groupId + "' relic", problems);
            final String relicName = section.getString("relic.name", groupId + " Relic");

            final ConfigurationSection mobsSection = section.getConfigurationSection("mobs");
            if (mobsSection == null) {
                problems.add("group '" + groupId + "' has no mobs");
                continue;
            }
            final List<SpawnerMob> mobs = new ArrayList<>();
            final Map<SpawnerVariant, SpawnerUpgradeCost> groupOverrides = parseCosts(
                    section.getConfigurationSection("upgrade-overrides"),
                    "group '" + groupId + "' upgrade-overrides", problems);
            for (final String mobId : mobsSection.getKeys(false)) {
                final SpawnerMob mob = parseMob(groupId, mobsSection.getConfigurationSection(mobId),
                        groupOverrides, problems);
                if (mob != null) {
                    mobs.add(mob);
                }
            }
            if (mobs.isEmpty()) {
                problems.add("group '" + groupId + "' has no valid mobs");
                continue;
            }
            if (essence == null || relic == null) {
                continue;
            }
            parsed.add(new SpawnerGroup(groupId, section.getString("name", groupId),
                    essence, essenceName, relic, relicName, mobs));
        }
        if (parsed.isEmpty()) {
            problems.add("no valid groups defined");
        }
        groups.clear();
        groups.addAll(parsed);
    }

    private SpawnerMob parseMob(final String groupId, final ConfigurationSection section,
                                final Map<SpawnerVariant, SpawnerUpgradeCost> groupOverrides,
                                final List<String> problems) {
        if (section == null) {
            return null;
        }
        final String mobId = section.getName();
        final String entityKey = section.getString("entity", "");
        EntityType entity;
        try {
            entity = EntityType.valueOf(entityKey.trim().toUpperCase());
        } catch (final IllegalArgumentException exception) {
            problems.add("group '" + groupId + "' mob '" + mobId + "': unknown entity '" + entityKey + "'");
            return null;
        }
        if (!entity.isAlive()) {
            problems.add("group '" + groupId + "' mob '" + mobId + "': entity " + entity + " is not a mob");
            return null;
        }
        final Material drop = material(section.getString("drop.item"),
                "group '" + groupId + "' mob '" + mobId + "' drop", problems);
        if (drop == null) {
            return null;
        }

        // Variant progression resolves mob-level `upgrades:` over the
        // group's `upgrade-overrides:` over the global `upgrade-defaults:`.
        final Map<SpawnerVariant, SpawnerUpgradeCost> upgrades =
                new EnumMap<>(SpawnerVariant.class);
        final Map<SpawnerVariant, SpawnerUpgradeCost> own = parseCosts(
                section.getConfigurationSection("upgrades"),
                "group '" + groupId + "' mob '" + mobId + "' upgrades", problems);
        for (final SpawnerVariant variant : SpawnerVariant.values()) {
            if (variant == SpawnerVariant.NORMAL) {
                continue;
            }
            SpawnerUpgradeCost cost = own.get(variant);
            if (cost == null) {
                cost = groupOverrides.get(variant);
            }
            if (cost == null) {
                cost = upgradeDefaults.get(variant);
            }
            if (cost == null) {
                problems.add("group '" + groupId + "' mob '" + mobId + "': missing upgrade cost for "
                        + variant.name().toLowerCase());
                continue;
            }
            if (cost.isEmpty()) {
                problems.add("group '" + groupId + "' mob '" + mobId + "': upgrade to "
                        + variant.name().toLowerCase() + " needs at least one material");
            }
            upgrades.put(variant, cost);
        }

        final Map<String, Integer> unlockDrops = new LinkedHashMap<>();
        final ConfigurationSection unlock = section.getConfigurationSection("unlock");
        if (unlock != null) {
            final ConfigurationSection dropsSection = unlock.getConfigurationSection("drops");
            if (dropsSection != null) {
                for (final String otherMob : dropsSection.getKeys(false)) {
                    unlockDrops.put(otherMob, Math.max(0, dropsSection.getInt(otherMob, 0)));
                }
            }
        }

        return new SpawnerMob(
                mobId,
                section.getString("name", mobId),
                entity,
                drop,
                section.getString("drop.name", mobId + " Drop"),
                Math.max(0, section.getDouble("spawner-cost", 0)),
                Math.max(0, unlock == null ? 0 : unlock.getInt("essence", 0)),
                unlockDrops,
                upgrades);
    }

    private Material material(final String key, final String where, final List<String> problems) {
        final Material material = Material.matchMaterial(key == null ? "" : key);
        if (material == null) {
            problems.add(where + ": unknown material '" + key + "'");
        }
        return material;
    }

    private double chance(final YamlConfiguration yaml, final String path, final double fallback,
                          final List<String> problems) {
        final double value = yaml.getDouble(path, fallback);
        if (value < 0 || value > 1) {
            problems.add(path + " " + value + " is not a chance between 0 and 1");
            return fallback;
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    public List<SpawnerGroup> groups() {
        return List.copyOf(groups);
    }

    public SpawnerGroup group(final String id) {
        for (final SpawnerGroup group : groups) {
            if (group.id().equals(id)) {
                return group;
            }
        }
        return null;
    }

    /** Group containing the given mob, or null. */
    public SpawnerGroup groupOf(final String mobId) {
        for (final SpawnerGroup group : groups) {
            if (group.mob(mobId) != null) {
                return group;
            }
        }
        return null;
    }

    /** Mob config for an entity type, or null (used on every mob death). */
    public SpawnerMob mobByEntity(final EntityType entity) {
        for (final SpawnerGroup group : groups) {
            for (final SpawnerMob mob : group.mobs()) {
                if (mob.entity() == entity) {
                    return mob;
                }
            }
        }
        return null;
    }

    public SpawnerVariantSettings variantSettings(final SpawnerVariant variant) {
        return variants.getOrDefault(variant, new SpawnerVariantSettings(1.0, 1, 6, false));
    }

    // ------------------------------------------------------------------
    // Numbers
    // ------------------------------------------------------------------

    /** Unique-drop chance for an island luck level: base + level * bonus, capped. */
    public double uniqueDropChance(final int luckLevel) {
        return Math.min(1.0, luckBaseChance + luckLevel * luckBonusPerLevel);
    }

    /** Coins for the given luck level (1-based), or -1 if out of range. */
    public double luckCost(final int level) {
        if (level < 1 || level > luckCosts.size()) {
            return -1;
        }
        return luckCosts.get(level - 1);
    }

    public double essenceChance() {
        return essenceChance;
    }

    public double relicChance() {
        return relicChance;
    }

    public boolean announceEssence() {
        return announceEssence;
    }

    public int luckMaxLevel() {
        return luckMaxLevel;
    }

    public int minDelayTicks() {
        return minDelayTicks;
    }

    public int maxDelayTicks() {
        return maxDelayTicks;
    }

    public int playerRange() {
        return playerRange;
    }

    /** How many spawners may share one block stack. */
    public int maxSpawnerStack() {
        return maxSpawnerStack;
    }

    /** Whether spawner-spawned mobs merge into counted stacks. */
    public boolean mobStackEnabled() {
        return mobStackEnabled;
    }

    /** Merge radius (blocks) for mob stacking. */
    public int mobStackRadius() {
        return mobStackRadius;
    }

    /** Highest count a single mob stack may reach. */
    public int mobStackMax() {
        return mobStackMax;
    }
}
