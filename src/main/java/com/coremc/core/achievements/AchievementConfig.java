package com.coremc.core.achievements;

import com.coremc.core.progress.ProgressAction;
import com.coremc.core.progress.ProgressSource;
import com.coremc.core.progress.reward.Reward;
import com.coremc.core.progress.reward.RewardType;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
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
 * Loads and validates {@code achievements.yml}: the Achievements, the
 * points each difficulty is worth, and the global settings.
 *
 * <p>All problems are collected into one exception — duplicate ids,
 * unknown categories, difficulties, actions, modes or materials,
 * requirements that are not positive, secret Achievements with no
 * description, and rewards that cannot be understood. A broken file
 * disables Achievements loudly instead of silently losing progress.</p>
 */
public final class AchievementConfig {

    private static final String FILE_NAME = "achievements.yml";

    private final JavaPlugin plugin;
    private final Map<String, Achievement> achievements = new LinkedHashMap<>();
    private final Map<AchievementCategory, List<Achievement>> byCategory =
            new EnumMap<>(AchievementCategory.class);
    private final Map<ProgressAction, List<Achievement>> byAction =
            new EnumMap<>(ProgressAction.class);
    private final Map<AchievementDifficulty, Integer> points =
            new EnumMap<>(AchievementDifficulty.class);

    private boolean enabled = true;
    private boolean announce = true;
    private boolean broadcastRare = true;

    public AchievementConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** A disabled config: no achievements, the menu says so instead of crashing. */
    public static AchievementConfig disabled() {
        final AchievementConfig config = new AchievementConfig(null);
        config.enabled = false;
        return config;
    }

    /** Extracts the default achievements.yml on first run, then parses and validates. */
    public void load() {
        final File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (final Exception failure) {
            throw new IllegalArgumentException("cannot read " + FILE_NAME + ": "
                    + failure.getMessage(), failure);
        }
        parse(yaml);
    }

    /** Parses and validates a YAML document (also used by the tests). */
    void parse(final YamlConfiguration yaml) {
        final List<String> problems = new ArrayList<>();
        achievements.clear();
        byCategory.clear();
        byAction.clear();
        points.clear();

        this.announce = yaml.getBoolean("settings.announce", true);
        this.broadcastRare = yaml.getBoolean("settings.broadcast-rare", true);
        for (final AchievementDifficulty difficulty : AchievementDifficulty.values()) {
            final int configured = yaml.getInt("settings.points." + difficulty.id(),
                    difficulty.defaultPoints());
            if (configured < 0) {
                problems.add("settings.points." + difficulty.id() + " cannot be negative");
            }
            points.put(difficulty, Math.max(0, configured));
        }

        final ConfigurationSection root = yaml.getConfigurationSection("achievements");
        if (root == null) {
            problems.add("missing 'achievements' mapping");
        } else {
            final Set<String> seen = new HashSet<>();
            for (final String rawId : root.getKeys(false)) {
                final ConfigurationSection section = root.getConfigurationSection(rawId);
                final String where = "achievements." + rawId;
                if (section == null) {
                    problems.add(where + " is not a section");
                    continue;
                }
                if (!seen.add(rawId.toLowerCase(Locale.ROOT))) {
                    problems.add(where + ": duplicate achievement id");
                    continue;
                }
                final Achievement achievement = parseOne(rawId, section, where, problems);
                if (achievement != null) {
                    achievements.put(achievement.id(), achievement);
                    byCategory.computeIfAbsent(achievement.category(),
                            ignored -> new ArrayList<>()).add(achievement);
                    byAction.computeIfAbsent(achievement.action(),
                            ignored -> new ArrayList<>()).add(achievement);
                }
            }
        }

        if (!problems.isEmpty()) {
            achievements.clear();
            byCategory.clear();
            byAction.clear();
            throw new IllegalArgumentException("broken " + FILE_NAME + ": "
                    + String.join("; ", problems));
        }
        this.enabled = true;
    }

    private Achievement parseOne(final String rawId, final ConfigurationSection section,
                                 final String where, final List<String> problems) {
        final AchievementCategory category = AchievementCategory.of(section.getString("category"));
        if (category == null) {
            problems.add(where + ".category: unknown category '"
                    + section.getString("category") + "'");
            return null;
        }
        final AchievementDifficulty difficulty =
                AchievementDifficulty.of(section.getString("difficulty", "common"));
        if (difficulty == null) {
            problems.add(where + ".difficulty: unknown difficulty '"
                    + section.getString("difficulty") + "'");
            return null;
        }
        final ProgressAction action = ProgressAction.of(section.getString("action"));
        if (action == null) {
            problems.add(where + ".action: unknown action '" + section.getString("action") + "'");
            return null;
        }
        final AchievementMode mode = AchievementMode.of(section.getString("mode", "total"));
        if (mode == null) {
            problems.add(where + ".mode: unknown mode '" + section.getString("mode") + "'");
            return null;
        }
        final long requirement = section.getLong("requirement", 1);
        if (requirement <= 0) {
            problems.add(where + ".requirement must be positive");
        }
        final Material icon = material(section.getString("icon"), where + ".icon", problems);
        final Set<String> keys = new LinkedHashSet<>();
        for (final String key : section.getStringList("keys")) {
            keys.add(key.trim().toLowerCase(Locale.ROOT));
        }
        final Set<ProgressSource> sources = new LinkedHashSet<>();
        for (final String raw : section.getStringList("sources")) {
            final ProgressSource source = ProgressSource.of(raw);
            if (source == null) {
                problems.add(where + ".sources: unknown source '" + raw + "'");
            } else {
                sources.add(source);
            }
        }
        if (mode == AchievementMode.UNIQUE && !keys.isEmpty() && keys.size() < requirement) {
            problems.add(where + ": unique requirement (" + requirement + ") is higher than the "
                    + keys.size() + " keys it accepts — impossible to earn");
        }
        final String description = section.getString("description", "");
        final boolean secret = section.getBoolean("secret", false);
        if (secret && description.isBlank()) {
            problems.add(where + ": secret achievements still need a description "
                    + "(shown once earned)");
        }
        final List<Reward> rewards = new ArrayList<>();
        for (final Map<?, ?> map : section.getMapList("rewards")) {
            final Reward reward = reward(map, where + ".rewards", problems);
            if (reward != null) {
                rewards.add(reward);
            }
        }
        return new Achievement(rawId, category, section.getString("display", rawId), description,
                icon == null ? category.icon() : icon, difficulty,
                section.getInt("points", points.getOrDefault(difficulty,
                        difficulty.defaultPoints())),
                action, keys, sources, mode, Math.max(1, requirement), secret,
                section.getBoolean("seasonal", category == AchievementCategory.SEASONAL), rewards);
    }

    private Reward reward(final Map<?, ?> map, final String where, final List<String> problems) {
        final RewardType type = RewardType.of(String.valueOf(map.get("type")));
        if (type == null) {
            problems.add(where + ": unknown reward type '" + map.get("type") + "'");
            return null;
        }
        final String id = map.get("id") == null ? "" : String.valueOf(map.get("id"));
        long amount = 0;
        if (map.get("amount") instanceof Number number) {
            amount = number.longValue();
        }
        if (type.amountBased() && amount <= 0) {
            problems.add(where + ": " + type.id() + " rewards need a positive amount");
            return null;
        }
        if (!type.amountBased() && id.isBlank()) {
            problems.add(where + ": " + type.id() + " rewards need an id");
            return null;
        }
        if (type == RewardType.ITEM && material(id, where + ".id", problems) == null) {
            return null;
        }
        return new Reward(type, id, amount,
                map.get("display") == null ? "" : String.valueOf(map.get("display")));
    }

    // ------------------------------------------------------------------
    // lookups
    // ------------------------------------------------------------------

    public boolean enabled() {
        return enabled;
    }

    public boolean announce() {
        return announce;
    }

    /** True when Epic and above are broadcast to the server. */
    public boolean broadcastRare() {
        return broadcastRare;
    }

    /** Points a difficulty is worth after config overrides. */
    public int points(final AchievementDifficulty difficulty) {
        return points.getOrDefault(difficulty, difficulty.defaultPoints());
    }

    /** Every achievement, in config order. */
    public List<Achievement> all() {
        return List.copyOf(achievements.values());
    }

    /** One achievement by id, or null. */
    public Achievement byId(final String id) {
        return id == null ? null : achievements.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Achievements of one category, in config order. */
    public List<Achievement> byCategory(final AchievementCategory category) {
        return List.copyOf(byCategory.getOrDefault(category, List.of()));
    }

    /** Achievements listening to one action (the hot path). */
    public List<Achievement> byAction(final ProgressAction action) {
        return List.copyOf(byAction.getOrDefault(action, List.of()));
    }

    /** Categories that actually have achievements, in enum order. */
    public List<AchievementCategory> categories() {
        final List<AchievementCategory> out = new ArrayList<>();
        for (final AchievementCategory category : AchievementCategory.values()) {
            if (!byCategory.getOrDefault(category, List.of()).isEmpty()) {
                out.add(category);
            }
        }
        return out;
    }

    /** The maximum points available on the server. */
    public int maxPoints() {
        int total = 0;
        for (final Achievement achievement : achievements.values()) {
            total += achievement.points();
        }
        return total;
    }

    private static Material material(final String raw, final String where,
                                     final List<String> problems) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            problems.add(where + ": unknown material '" + raw + "'");
            return null;
        }
    }
}
