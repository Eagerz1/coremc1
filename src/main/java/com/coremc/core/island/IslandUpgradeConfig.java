package com.coremc.core.island;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import com.coremc.core.config.CoreConfig;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and validates upgrades.yml: the permanent island upgrades
 * (claim size, member slots) and the timed island buffs (crop growth,
 * spawner boost, XP boost). Broken files disable the upgrade system
 * with one loud exception — never half-work — and leave the member
 * limit uncapped (the pre-upgrade behaviour).
 */
public final class IslandUpgradeConfig {

    private static final String FILE_NAME = "upgrades.yml";

    /** Permanent claim-size upgrade definition. */
    public record ClaimSizeDef(String name, Material icon, int growthPerLevel, List<Double> steps) {
        public int maxLevel() {
            return steps.size();
        }
    }

    /** Permanent member-slot upgrade definition. */
    public record MemberSlotsDef(String name, Material icon, int baseSlots, int slotsPerLevel,
                                 List<Double> steps) {
        public int maxLevel() {
            return steps.size();
        }
    }

    /** Timed buff definition. */
    public record BuffDef(String id, String name, Material icon, int durationMinutes,
                          double price, double multiplier) {
    }

    private final JavaPlugin plugin;
    private final CoreConfig coreConfig;
    private boolean enabled = true;
    private ClaimSizeDef claimSize;
    private MemberSlotsDef memberSlots;
    private Map<String, BuffDef> buffs = new LinkedHashMap<>();

    public IslandUpgradeConfig(final JavaPlugin plugin, final CoreConfig coreConfig) {
        this.plugin = plugin;
        this.coreConfig = coreConfig;
    }

    /** Extracts the default upgrades.yml on first run, then parses and validates. */
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
        parse(yaml, coreConfig.islandBorderSize(), coreConfig.islandSpacing());
    }

    /** Parses and validates a YAML document (also used by tests). */
    void parse(final YamlConfiguration yaml, final int baseBorderSize, final int gridSpacing) {
        final List<String> problems = new ArrayList<>();

        final ConfigurationSection upgrades = yaml.getConfigurationSection("upgrades");
        if (upgrades == null) {
            problems.add("missing 'upgrades' mapping");
        } else {
            this.claimSize = parseClaimSize(upgrades.getConfigurationSection("claim-size"), problems);
            this.memberSlots = parseMemberSlots(upgrades.getConfigurationSection("member-slots"), problems);
        }

        final ConfigurationSection buffsSection = yaml.getConfigurationSection("buffs");
        final Map<String, BuffDef> parsedBuffs = new LinkedHashMap<>();
        if (buffsSection == null) {
            problems.add("missing 'buffs' mapping");
        } else {
            for (final String id : buffsSection.getKeys(false)) {
                final ConfigurationSection section = buffsSection.getConfigurationSection(id);
                if (section == null) {
                    problems.add("buff '" + id + "' is not a mapping");
                    continue;
                }
                final Material icon = material(section.getString("icon"), "buff '" + id + "'", problems);
                final double multiplier = section.getDouble("multiplier", 2.0);
                if (multiplier < 1.0) {
                    problems.add("buff '" + id + "': multiplier must be at least 1");
                }
                final int duration = section.getInt("duration-minutes", 30);
                if (duration <= 0) {
                    problems.add("buff '" + id + "': duration-minutes must be positive");
                }
                if (icon != null) {
                    parsedBuffs.put(id, new BuffDef(id,
                            section.getString("name", id), icon,
                            duration,
                            Math.max(0, section.getDouble("price", 0)),
                            multiplier));
                }
            }
        }

        if (claimSize != null && gridSpacing > 0) {
            final int maxClaim = baseBorderSize + claimSize.growthPerLevel() * claimSize.maxLevel();
            if (maxClaim >= gridSpacing) {
                problems.add("upgrades.claim-size: fully upgraded claim " + maxClaim
                        + " must stay below the island spacing " + gridSpacing
                        + " (neighbouring claims would overlap)");
            }
        }

        if (!problems.isEmpty()) {
            this.enabled = false;
            throw new IllegalArgumentException("broken " + FILE_NAME + ": "
                    + String.join("; ", problems));
        }
        this.buffs = parsedBuffs;
        this.enabled = true;
    }

    private ClaimSizeDef parseClaimSize(final ConfigurationSection section,
                                        final List<String> problems) {
        if (section == null) {
            problems.add("upgrades.claim-size is missing");
            return null;
        }
        final Material icon = material(section.getString("icon"), "upgrades.claim-size", problems);
        final int growth = section.getInt("growth-per-level", 10);
        if (growth <= 0) {
            problems.add("upgrades.claim-size: growth-per-level must be positive");
        }
        final List<Double> steps = parseSteps(section, "upgrades.claim-size", problems);
        if (icon == null || steps == null) {
            return null;
        }
        return new ClaimSizeDef(section.getString("name", "Island Expansion"), icon, growth, steps);
    }

    private MemberSlotsDef parseMemberSlots(final ConfigurationSection section,
                                            final List<String> problems) {
        if (section == null) {
            problems.add("upgrades.member-slots is missing");
            return null;
        }
        final Material icon = material(section.getString("icon"), "upgrades.member-slots", problems);
        final int base = section.getInt("base-slots", 3);
        if (base < 0) {
            problems.add("upgrades.member-slots: base-slots cannot be negative");
        }
        final int perLevel = section.getInt("slots-per-level", 1);
        if (perLevel <= 0) {
            problems.add("upgrades.member-slots: slots-per-level must be positive");
        }
        final List<Double> steps = parseSteps(section, "upgrades.member-slots", problems);
        if (icon == null || steps == null) {
            return null;
        }
        return new MemberSlotsDef(section.getString("name", "Member Slots"), icon, base, perLevel, steps);
    }

    private List<Double> parseSteps(final ConfigurationSection section, final String where,
                                    final List<String> problems) {
        final List<Double> steps = new ArrayList<>();
        for (final Object raw : section.getList("steps", List.of())) {
            try {
                final double price = Double.parseDouble(String.valueOf(raw));
                if (price < 0) {
                    problems.add(where + ": step prices cannot be negative");
                } else {
                    steps.add(price);
                }
            } catch (final NumberFormatException exception) {
                problems.add(where + ": steps contains a non-number: " + raw);
            }
        }
        if (steps.isEmpty()) {
            problems.add(where + ": needs at least one step price");
            return null;
        }
        return List.copyOf(steps);
    }

    private Material material(final String key, final String where, final List<String> problems) {
        final Material material = Material.matchMaterial(key == null ? "" : key);
        if (material == null) {
            problems.add(where + ": unknown material '" + key + "'");
        }
        return material;
    }

    // ------------------------------------------------------------------
    // Fallback for a broken config: upgrades/buffs disabled, no caps.
    // ------------------------------------------------------------------

    /** A disabled config: no upgrades, no buffs, uncapped members. */
    public static IslandUpgradeConfig disabled() {
        final IslandUpgradeConfig config = new IslandUpgradeConfig(null, null);
        config.enabled = false;
        config.claimSize = new ClaimSizeDef("Island Expansion", Material.GRASS_BLOCK, 0, List.of());
        config.memberSlots = new MemberSlotsDef("Member Slots", Material.PLAYER_HEAD,
                Integer.MAX_VALUE, 0, List.of());
        config.buffs = Map.of();
        return config;
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    public boolean enabled() {
        return enabled;
    }

    public ClaimSizeDef claimSize() {
        return claimSize;
    }

    public MemberSlotsDef memberSlots() {
        return memberSlots;
    }

    public List<BuffDef> buffs() {
        return List.copyOf(buffs.values());
    }

    public BuffDef buff(final String id) {
        return buffs.get(id);
    }

    /** Coin price of claim-size level {@code level} (1-based), or -1 if out of range. */
    public double claimSizePrice(final int level) {
        if (claimSize == null || level < 1 || level > claimSize.maxLevel()) {
            return -1;
        }
        return claimSize.steps().get(level - 1);
    }

    /** Coin price of member-slots level {@code level} (1-based), or -1 if out of range. */
    public double memberSlotsPrice(final int level) {
        if (memberSlots == null || level < 1 || level > memberSlots.maxLevel()) {
            return -1;
        }
        return memberSlots.steps().get(level - 1);
    }

    /** Maximum member count for an island with the given member-slots level. */
    public int memberLimit(final int memberSlotLevel) {
        if (memberSlots == null || !enabled) {
            return Integer.MAX_VALUE;
        }
        return memberSlots.baseSlots() + memberSlots.slotsPerLevel()
                * Math.max(0, Math.min(memberSlotLevel, memberSlots.maxLevel()));
    }

    /** Border claim size for an island with the given claim-size level. */
    public int borderSizeFor(final int baseBorderSize, final int claimSizeLevel) {
        if (claimSize == null || !enabled) {
            return baseBorderSize;
        }
        final int level = Math.max(0, Math.min(claimSizeLevel, claimSize.maxLevel()));
        return baseBorderSize + claimSize.growthPerLevel() * level;
    }
}
