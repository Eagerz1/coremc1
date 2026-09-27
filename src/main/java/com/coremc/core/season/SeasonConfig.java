package com.coremc.core.season;

import com.coremc.core.quest.QuestConfig;
import java.io.File;
import java.time.Instant;
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

/** Parsed season.yml: one current season, level curve, source XP and rewards. */
public final class SeasonConfig {

    public static final String FILE_NAME = "season.yml";
    private static final Set<String> SUPPORTED_REWARDS = Set.of(
            "sky-tokens", "island-xp", "credits", "key", "keys", "item", "core-fragment", "core-fragments",
            "generator-material", "companion-material", "omnitool-material", "tag", "cosmetic",
            "seasonal-collectible", "seasonal-xp", "lootbox", "lootboxes", "title", "trophy");

    public record SeasonDef(String id, String name, long startAt, long endAt) {
        public boolean active(final long now) {
            return now >= startAt && now < endAt;
        }
    }

    public record Reward(String id, String type, long amount, String item, String display) {
        public String key() {
            return id.isBlank() ? normalise(type) + ":" + normalise(item) + ":" + amount : id;
        }
    }

    public record Tier(int level, Material icon, List<Reward> freeRewards, List<Reward> premiumRewards) {
    }

    private final JavaPlugin plugin;
    private boolean enabled = true;
    private SeasonDef season = new SeasonDef("season-1", "Season 1", 0L, 0L);
    private int maxLevel = 50;
    /** cumulative XP required to reach each level, with level 1 = 0 */
    private final Map<Integer, Long> xpRequirements = new LinkedHashMap<>();
    private final Map<SeasonXpSource, Long> sourceXp = new LinkedHashMap<>();
    private final Map<Integer, Tier> tiers = new LinkedHashMap<>();
    private boolean premiumEnabled;

    public SeasonConfig(final JavaPlugin plugin) {
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
            throw new IllegalArgumentException("cannot read " + FILE_NAME + ": " + exception.getMessage(), exception);
        }
        parse(yaml);
    }

    void parse(final YamlConfiguration yaml) {
        final List<String> problems = new ArrayList<>();
        final String id = normalise(yaml.getString("season.id", ""));
        if (id.isBlank()) {
            problems.add("season.id is required");
        }
        final String name = yaml.getString("season.name", QuestConfig.title(id));
        final long start = parseTime(yaml.getString("season.start", ""), "season.start", problems);
        final long end = parseTime(yaml.getString("season.end", ""), "season.end", problems);
        if (start > 0L && end > 0L && end <= start) {
            problems.add("season.end must be after season.start");
        }
        this.maxLevel = Math.max(1, yaml.getInt("levels.max-level", 50));
        if (maxLevel < 10 || maxLevel > 100) {
            problems.add("levels.max-level must be between 10 and 100");
        }
        final Map<Integer, Long> parsedReq = new LinkedHashMap<>();
        final ConfigurationSection req = yaml.getConfigurationSection("levels.xp-required");
        if (req == null) {
            problems.add("levels.xp-required is required");
        } else {
            for (int level = 1; level <= maxLevel; level++) {
                if (!req.contains(String.valueOf(level))) {
                    problems.add("levels.xp-required." + level + " is required");
                    continue;
                }
                parsedReq.put(level, Math.max(0L, req.getLong(String.valueOf(level))));
            }
        }
        long last = -1L;
        for (int level = 1; level <= maxLevel; level++) {
            final long value = parsedReq.getOrDefault(level, -1L);
            if (level == 1 && value != 0L) {
                problems.add("levels.xp-required.1 must be 0");
            }
            if (value <= last) {
                problems.add("levels.xp-required must increase monotonically at level " + level);
            }
            last = value;
        }

        final Map<SeasonXpSource, Long> parsedSources = new LinkedHashMap<>();
        final ConfigurationSection sources = yaml.getConfigurationSection("sources");
        if (sources == null) {
            problems.add("sources mapping is required");
        } else {
            for (final String raw : sources.getKeys(false)) {
                try {
                    final SeasonXpSource source = SeasonXpSource.parse(raw);
                    final long amount = sources.getLong(raw, 0L);
                    if (amount < 0L) {
                        problems.add("sources." + raw + " cannot be negative");
                    }
                    parsedSources.put(source, Math.max(0L, amount));
                } catch (final IllegalArgumentException exception) {
                    problems.add("sources." + raw + " is not a known Season XP source");
                }
            }
        }
        for (final SeasonXpSource source : SeasonXpSource.values()) {
            parsedSources.putIfAbsent(source, 0L);
        }

        this.premiumEnabled = yaml.getBoolean("premium.enabled", false);
        final Map<Integer, Tier> parsedTiers = new LinkedHashMap<>();
        final ConfigurationSection rewardRoot = yaml.getConfigurationSection("rewards.levels");
        if (rewardRoot == null) {
            problems.add("rewards.levels mapping is required");
        } else {
            final Set<Integer> seen = new LinkedHashSet<>();
            for (final String rawLevel : rewardRoot.getKeys(false)) {
                final int level;
                try {
                    level = Integer.parseInt(rawLevel);
                } catch (final NumberFormatException exception) {
                    problems.add("rewards.levels." + rawLevel + " must be a level number");
                    continue;
                }
                if (level < 1 || level > maxLevel) {
                    problems.add("rewards.levels." + rawLevel + " is outside max level");
                    continue;
                }
                if (!seen.add(level)) {
                    problems.add("duplicate rewards level " + level);
                }
                final ConfigurationSection section = rewardRoot.getConfigurationSection(rawLevel);
                if (section == null) {
                    problems.add("rewards.levels." + rawLevel + " is not a mapping");
                    continue;
                }
                Material icon = Material.CHEST;
                try {
                    icon = Material.valueOf(section.getString("icon", "CHEST").toUpperCase(Locale.ROOT));
                } catch (final IllegalArgumentException exception) {
                    problems.add("rewards.levels." + rawLevel + ".icon is invalid");
                }
                parsedTiers.put(level, new Tier(level, icon,
                        rewards(section.getConfigurationSection("free"), "rewards.levels." + rawLevel + ".free", problems),
                        rewards(section.getConfigurationSection("premium"), "rewards.levels." + rawLevel + ".premium", problems)));
            }
        }
        if (parsedTiers.isEmpty()) {
            problems.add("at least one rewards.levels entry is required");
        }
        if (!problems.isEmpty()) {
            enabled = false;
            throw new IllegalArgumentException("broken " + FILE_NAME + ": " + String.join("; ", problems));
        }
        this.season = new SeasonDef(id, name, start, end);
        xpRequirements.clear();
        xpRequirements.putAll(parsedReq);
        sourceXp.clear();
        sourceXp.putAll(parsedSources);
        tiers.clear();
        tiers.putAll(parsedTiers);
        enabled = true;
    }

    private List<Reward> rewards(final ConfigurationSection section, final String path, final List<String> problems) {
        final List<Reward> parsed = new ArrayList<>();
        if (section == null) {
            return parsed;
        }
        for (final String key : section.getKeys(false)) {
            final ConfigurationSection reward = section.getConfigurationSection(key);
            if (reward == null) {
                problems.add(path + "." + key + " is not a mapping");
                continue;
            }
            final String type = normalise(reward.getString("type", key));
            final long amount = reward.getLong("amount", 1L);
            if (!SUPPORTED_REWARDS.contains(type)) {
                problems.add(path + "." + key + ".type is unsupported: " + type);
            }
            if (amount <= 0L) {
                problems.add(path + "." + key + ".amount must be positive");
            }
            parsed.add(new Reward(normalise(key), type, Math.max(1L, amount),
                    normalise(reward.getString("item", "")), reward.getString("display", "")));
        }
        return parsed;
    }

    private long parseTime(final String raw, final String path, final List<String> problems) {
        if (raw == null || raw.isBlank()) {
            problems.add(path + " is required (ISO-8601 instant)");
            return 0L;
        }
        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (final Exception exception) {
            problems.add(path + " must be an ISO-8601 instant");
            return 0L;
        }
    }

    public static SeasonConfig disabled() {
        final SeasonConfig config = new SeasonConfig(null);
        config.enabled = false;
        return config;
    }

    public boolean enabled() { return enabled; }
    public SeasonDef season() { return season; }
    public int maxLevel() { return maxLevel; }
    public boolean premiumEnabled() { return premiumEnabled; }
    public long sourceXp(final SeasonXpSource source) { return sourceXp.getOrDefault(source, 0L); }
    public Map<SeasonXpSource, Long> sourceXp() { return Map.copyOf(sourceXp); }
    public List<Tier> tiers() { return List.copyOf(tiers.values()); }
    public Tier tier(final int level) { return tiers.get(level); }
    public long xpForLevel(final int level) {
        return xpRequirements.getOrDefault(Math.max(1, Math.min(maxLevel, level)), Long.MAX_VALUE);
    }
    public long maxXp() { return xpForLevel(maxLevel); }
    public int levelForXp(final long xp) {
        int result = 1;
        final long value = Math.max(0L, xp);
        for (int level = 1; level <= maxLevel; level++) {
            if (value >= xpForLevel(level)) {
                result = level;
            }
        }
        return result;
    }
    public long nextXpFor(final long xp) {
        final int level = levelForXp(xp);
        if (level >= maxLevel) {
            return xpForLevel(maxLevel);
        }
        return xpForLevel(level + 1);
    }

    public static String normalise(final String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
