package com.coremc.core.quest;

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
 * Typed loader for quests.yml. Quest templates stay stable by id so progress
 * survives balance edits, and broken configs fail loudly rather than assigning
 * impossible goals silently.
 */
public final class QuestConfig {

    public enum Scope {
        DAILY, WEEKLY, ISLAND, SEASONAL, CONTRACT
    }

    public static final String FILE_NAME = "quests.yml";

    public record ObjectiveDef(String id, String action, long target, Map<String, String> filters) {
        public boolean matches(final QuestAction actionEvent) {
            if (!action.equals(normalise(actionEvent.action()))) {
                return false;
            }
            for (final Map.Entry<String, String> filter : filters.entrySet()) {
                final String value = actionEvent.metadata().getOrDefault(normalise(filter.getKey()), "");
                if (!normalise(filter.getValue()).equals(normalise(value))) {
                    return false;
                }
            }
            return true;
        }
    }

    public record RewardDef(String id, String type, long amount, String item, String scope) {
        public String key() {
            return id.isBlank() ? normalise(type) + ":" + normalise(item) + ":" + amount + ":" + normalise(scope) : id;
        }
    }

    public record Requirements(int islandLevel, Set<String> systems, Set<String> anyMastery) {
        static Requirements none() {
            return new Requirements(0, Set.of(), Set.of());
        }
    }

    public record QuestTemplate(String id, Scope scope, String name, Material icon, List<String> lore,
                                int weight, List<String> pools, List<String> incompatible,
                                Requirements requirements, List<ObjectiveDef> objectives,
                                List<RewardDef> rewards, boolean visibleWhenLocked) {
    }

    public record StreakMilestone(int days, List<RewardDef> rewards) {
    }

    private final JavaPlugin plugin;
    private boolean enabled = true;
    private int dailyCount = 4;
    private int weeklyCount = 4;
    private int islandChallengeCount = 3;
    private long dailyPeriodMillis = 24L * 60L * 60L * 1000L;
    private long weeklyPeriodMillis = 7L * 24L * 60L * 60L * 1000L;
    private long islandChallengePeriodMillis = 7L * 24L * 60L * 60L * 1000L;
    private boolean streakEnabled = true;
    private final List<StreakMilestone> streakMilestones = new ArrayList<>();
    private final Map<String, QuestTemplate> templates = new LinkedHashMap<>();

    public QuestConfig(final JavaPlugin plugin) {
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
        this.dailyCount = clamp(yaml.getInt("settings.daily.count", 4), 3, 5);
        this.weeklyCount = clamp(yaml.getInt("settings.weekly.count", 4), 3, 5);
        this.islandChallengeCount = clamp(yaml.getInt("settings.island-challenges.count", 3), 1, 5);
        this.dailyPeriodMillis = Math.max(1L, yaml.getLong("settings.daily.reset-hours", 24L)) * 3_600_000L;
        this.weeklyPeriodMillis = Math.max(1L, yaml.getLong("settings.weekly.reset-hours", 168L)) * 3_600_000L;
        this.islandChallengePeriodMillis = Math.max(1L,
                yaml.getLong("settings.island-challenges.reset-hours", 168L)) * 3_600_000L;
        this.streakEnabled = yaml.getBoolean("settings.streak.enabled", true);

        final List<StreakMilestone> parsedMilestones = new ArrayList<>();
        final ConfigurationSection streakSection = yaml.getConfigurationSection("settings.streak.milestones");
        if (streakSection != null) {
            for (final String key : streakSection.getKeys(false)) {
                final int days;
                try {
                    days = Integer.parseInt(key);
                } catch (final NumberFormatException exception) {
                    problems.add("settings.streak.milestones." + key + " must be a number of days");
                    continue;
                }
                parsedMilestones.add(new StreakMilestone(days,
                        rewards(streakSection.getConfigurationSection(key + ".rewards"),
                                "settings.streak.milestones." + key, problems)));
            }
        }

        final Map<String, QuestTemplate> parsed = new LinkedHashMap<>();
        final ConfigurationSection root = yaml.getConfigurationSection("templates");
        if (root == null) {
            problems.add("missing 'templates' mapping");
        } else {
            for (final String rawId : root.getKeys(false)) {
                final String id = normalise(rawId);
                final ConfigurationSection section = root.getConfigurationSection(rawId);
                if (section == null) {
                    problems.add("templates." + rawId + " is not a mapping");
                    continue;
                }
                final Scope scope = scope(section.getString("type", "daily"), problems, rawId);
                final int weight = section.getInt("weight", 1);
                if (weight < 0) {
                    problems.add("templates." + rawId + ".weight cannot be negative");
                }
                Material icon = Material.BOOK;
                try {
                    icon = Material.valueOf(section.getString("icon", "BOOK").toUpperCase(Locale.ROOT));
                } catch (final IllegalArgumentException exception) {
                    problems.add("templates." + rawId + ".icon is not a valid material");
                }
                final List<ObjectiveDef> objectives = objectives(section, rawId, problems);
                final List<RewardDef> rewards = rewards(section.getConfigurationSection("rewards"),
                        "templates." + rawId, problems);
                if (objectives.isEmpty()) {
                    problems.add("templates." + rawId + " needs at least one objective");
                }
                if (rewards.isEmpty()) {
                    problems.add("templates." + rawId + " needs at least one reward");
                }
                if (parsed.containsKey(id)) {
                    problems.add("duplicate template id '" + id + "'");
                }
                parsed.put(id, new QuestTemplate(id, scope, section.getString("name", title(id)), icon,
                        section.getStringList("lore"), Math.max(0, weight),
                        normaliseList(section.getStringList("pools")),
                        normaliseList(section.getStringList("incompatible")),
                        requirements(section.getConfigurationSection("requirements")), objectives, rewards,
                        section.getBoolean("visible-when-locked", false)));
            }
        }
        if (parsed.values().stream().noneMatch(t -> t.scope() == Scope.DAILY)) {
            problems.add("at least one daily template is required");
        }
        if (parsed.values().stream().noneMatch(t -> t.scope() == Scope.WEEKLY)) {
            problems.add("at least one weekly template is required");
        }
        if (parsed.values().stream().noneMatch(t -> t.scope() == Scope.ISLAND)) {
            problems.add("at least one island challenge template is required");
        }
        if (!problems.isEmpty()) {
            enabled = false;
            throw new IllegalArgumentException("broken " + FILE_NAME + ": " + String.join("; ", problems));
        }
        templates.clear();
        templates.putAll(parsed);
        streakMilestones.clear();
        streakMilestones.addAll(parsedMilestones);
        enabled = true;
    }

    private List<ObjectiveDef> objectives(final ConfigurationSection section, final String rawId,
                                          final List<String> problems) {
        final List<ObjectiveDef> parsed = new ArrayList<>();
        if (section.isConfigurationSection("objective")) {
            final ConfigurationSection objective = section.getConfigurationSection("objective");
            if (objective != null) {
                parsed.add(objective(objective, "main", "templates." + rawId + ".objective", problems));
            }
        }
        final ConfigurationSection objectives = section.getConfigurationSection("objectives");
        if (objectives != null) {
            for (final String key : objectives.getKeys(false)) {
                final ConfigurationSection objective = objectives.getConfigurationSection(key);
                if (objective == null) {
                    problems.add("templates." + rawId + ".objectives." + key + " is not a mapping");
                } else {
                    parsed.add(objective(objective, normalise(key),
                            "templates." + rawId + ".objectives." + key, problems));
                }
            }
        }
        return parsed;
    }

    private ObjectiveDef objective(final ConfigurationSection section, final String fallbackId,
                                   final String path, final List<String> problems) {
        final String action = normalise(section.getString("action", ""));
        final long target = section.getLong("target", 0L);
        if (action.isBlank()) {
            problems.add(path + ".action is required");
        }
        if (target <= 0L) {
            problems.add(path + ".target must be positive");
        }
        final Map<String, String> filters = new LinkedHashMap<>();
        final ConfigurationSection filterSection = section.getConfigurationSection("filters");
        if (filterSection != null) {
            for (final String key : filterSection.getKeys(false)) {
                filters.put(normalise(key), normalise(String.valueOf(filterSection.get(key))));
            }
        }
        return new ObjectiveDef(normalise(section.getString("id", fallbackId)), action,
                Math.max(1L, target), Map.copyOf(filters));
    }

    private List<RewardDef> rewards(final ConfigurationSection section, final String path,
                                    final List<String> problems) {
        final List<RewardDef> parsed = new ArrayList<>();
        if (section == null) {
            return parsed;
        }
        for (final String key : section.getKeys(false)) {
            final ConfigurationSection reward = section.getConfigurationSection(key);
            if (reward == null) {
                problems.add(path + ".rewards." + key + " is not a mapping");
                continue;
            }
            final String type = normalise(reward.getString("type", key));
            final long amount = reward.getLong("amount", 0L);
            if (type.isBlank()) {
                problems.add(path + ".rewards." + key + ".type is required");
            }
            if (amount <= 0L) {
                problems.add(path + ".rewards." + key + ".amount must be positive");
            }
            parsed.add(new RewardDef(normalise(key), type, Math.max(1L, amount),
                    normalise(reward.getString("item", "")),
                    normalise(reward.getString("scope", "player"))));
        }
        return parsed;
    }

    private Requirements requirements(final ConfigurationSection section) {
        if (section == null) {
            return Requirements.none();
        }
        return new Requirements(Math.max(0, section.getInt("island-level", 0)),
                new LinkedHashSet<>(normaliseList(section.getStringList("systems"))),
                new LinkedHashSet<>(normaliseList(section.getStringList("any-mastery"))));
    }

    private Scope scope(final String raw, final List<String> problems, final String rawId) {
        final String normalised = normalise(raw);
        return switch (normalised) {
            case "daily" -> Scope.DAILY;
            case "weekly" -> Scope.WEEKLY;
            case "island", "island-challenge", "challenge" -> Scope.ISLAND;
            case "seasonal" -> Scope.SEASONAL;
            case "contract", "competitive-contract" -> Scope.CONTRACT;
            default -> {
                problems.add("templates." + rawId + ".type is unknown: " + raw);
                yield Scope.DAILY;
            }
        };
    }

    public static QuestConfig disabled() {
        final QuestConfig config = new QuestConfig(null);
        config.enabled = false;
        return config;
    }

    public boolean enabled() {
        return enabled;
    }

    public int dailyCount() {
        return dailyCount;
    }

    public int weeklyCount() {
        return weeklyCount;
    }

    public int islandChallengeCount() {
        return islandChallengeCount;
    }

    public long dailyPeriodMillis() {
        return dailyPeriodMillis;
    }

    public long weeklyPeriodMillis() {
        return weeklyPeriodMillis;
    }

    public long islandChallengePeriodMillis() {
        return islandChallengePeriodMillis;
    }

    public boolean streakEnabled() {
        return streakEnabled;
    }

    public List<StreakMilestone> streakMilestones() {
        return List.copyOf(streakMilestones);
    }

    public QuestTemplate template(final String id) {
        return templates.get(normalise(id));
    }

    public List<QuestTemplate> templates(final Scope scope) {
        final List<QuestTemplate> result = new ArrayList<>();
        for (final QuestTemplate template : templates.values()) {
            if (template.scope() == scope) {
                result.add(template);
            }
        }
        return result;
    }

    public List<QuestTemplate> allTemplates() {
        return List.copyOf(templates.values());
    }

    public static String normalise(final String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static String title(final String id) {
        final String[] parts = normalise(id).replace('-', ' ').split(" ");
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

    private static List<String> normaliseList(final List<String> input) {
        final List<String> result = new ArrayList<>();
        for (final String value : input) {
            final String normalised = normalise(value);
            if (!normalised.isBlank()) {
                result.add(normalised);
            }
        }
        return result;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
