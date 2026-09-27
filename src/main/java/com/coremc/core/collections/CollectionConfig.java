package com.coremc.core.collections;

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
 * Loads and validates {@code collections.yml}: the permanent
 * Collection entries, their per-entry milestone curves, their rewards
 * and the Collection-locked recipes.
 *
 * <p>Like every other CoreMC config, all problems are collected into
 * one loud exception — duplicate ids, unknown categories, actions or
 * materials, milestone amounts that do not ascend, rewards pointing at
 * recipes or Collections that do not exist, malformed recipes, hidden
 * entries with no source hint. A broken file disables Collections with
 * a single log line and never corrupts saved player data.</p>
 *
 * <p>The parsed result is cached in this object: entries by id, by
 * category and by action, so the hot path (an event arriving) is a map
 * lookup rather than a config read.</p>
 */
public final class CollectionConfig {

    private static final String FILE_NAME = "collections.yml";

    private final JavaPlugin plugin;
    private final Map<String, CollectionEntry> entries = new LinkedHashMap<>();
    private final Map<CollectionCategory, List<CollectionEntry>> byCategory =
            new EnumMap<>(CollectionCategory.class);
    private final Map<ProgressAction, List<CollectionEntry>> byAction =
            new EnumMap<>(ProgressAction.class);
    private final Map<String, UnlockableRecipe> recipes = new LinkedHashMap<>();

    private boolean enabled = true;
    private int entriesPerPage = 28;
    private boolean announceMilestones = true;

    public CollectionConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** A disabled config: no entries, the menu says so instead of crashing. */
    public static CollectionConfig disabled() {
        final CollectionConfig config = new CollectionConfig(null);
        config.enabled = false;
        return config;
    }

    /** Extracts the default collections.yml on first run, then parses and validates. */
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
        entries.clear();
        byCategory.clear();
        byAction.clear();
        recipes.clear();

        this.entriesPerPage = clamp(yaml.getInt("settings.entries-per-page", 28), 7, 45);
        this.announceMilestones = yaml.getBoolean("settings.announce-milestones", true);

        parseRecipes(yaml, problems);
        parseEntries(yaml, problems);
        validateRewardTargets(problems);

        if (!problems.isEmpty()) {
            entries.clear();
            byCategory.clear();
            byAction.clear();
            recipes.clear();
            throw new IllegalArgumentException("broken " + FILE_NAME + ": "
                    + String.join("; ", problems));
        }
        this.enabled = true;
    }

    // ------------------------------------------------------------------
    // parsing
    // ------------------------------------------------------------------

    private void parseRecipes(final YamlConfiguration yaml, final List<String> problems) {
        final ConfigurationSection root = yaml.getConfigurationSection("recipes");
        if (root == null) {
            return; // recipes are optional
        }
        for (final String rawId : root.getKeys(false)) {
            final String id = rawId.toLowerCase(Locale.ROOT);
            final String where = "recipes." + rawId;
            final ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                problems.add(where + " is not a section");
                continue;
            }
            if (recipes.containsKey(id)) {
                problems.add(where + ": duplicate recipe id");
                continue;
            }
            final Material icon = material(section.getString("icon"), where + ".icon", problems);
            final Material result = material(section.getString("result"), where + ".result", problems);
            final String collectionId = section.getString("requires.collection", "")
                    .trim().toLowerCase(Locale.ROOT);
            final int tier = section.getInt("requires.tier", 1);
            if (collectionId.isEmpty()) {
                problems.add(where + ".requires.collection is missing");
            }
            if (tier < 1) {
                problems.add(where + ".requires.tier must be at least 1");
            }
            final List<UnlockableRecipe.Ingredient> ingredients = new ArrayList<>();
            for (final String raw : section.getStringList("ingredients")) {
                final UnlockableRecipe.Ingredient ingredient =
                        ingredient(raw, where + ".ingredients", problems);
                if (ingredient != null) {
                    ingredients.add(ingredient);
                }
            }
            if (ingredients.isEmpty()) {
                problems.add(where + ".ingredients is empty");
            }
            if (result == null) {
                continue;
            }
            recipes.put(id, new UnlockableRecipe(id, section.getString("display", rawId),
                    icon == null ? result : icon, collectionId, tier, ingredients, result,
                    section.getInt("result-amount", 1), section.getString("description", "")));
        }
    }

    private void parseEntries(final YamlConfiguration yaml, final List<String> problems) {
        final ConfigurationSection root = yaml.getConfigurationSection("collections");
        if (root == null) {
            problems.add("missing 'collections' mapping");
            return;
        }
        final Set<String> seenIds = new HashSet<>();
        final Set<String> seenSubjects = new HashSet<>();
        for (final String rawId : root.getKeys(false)) {
            final String id = rawId.toLowerCase(Locale.ROOT);
            final String where = "collections." + rawId;
            final ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                problems.add(where + " is not a section");
                continue;
            }
            if (!seenIds.add(id)) {
                problems.add(where + ": duplicate collection id");
                continue;
            }
            final CollectionCategory category = CollectionCategory.of(section.getString("category"));
            if (category == null) {
                problems.add(where + ".category: unknown category '"
                        + section.getString("category") + "'");
                continue;
            }
            final ProgressAction action = ProgressAction.of(section.getString("action"));
            if (action == null) {
                problems.add(where + ".action: unknown action '"
                        + section.getString("action") + "'");
                continue;
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
            final boolean hidden = section.getBoolean("hidden", false);
            final String hint = section.getString("hint", "");
            if (hidden && hint.isBlank()) {
                problems.add(where + ": hidden entries need a 'hint' (a vague source, never the odds)");
            }
            // one subject may only feed one entry per category, so a single
            // action can never be double-counted inside the same Collection
            for (final String key : keys.isEmpty() ? Set.of("*") : keys) {
                final String subject = category.id() + "|" + action.id() + "|" + key;
                if (!seenSubjects.add(subject)) {
                    problems.add(where + ": '" + key + "' already feeds another "
                            + category.id() + " collection for " + action.id());
                }
            }
            final List<CollectionMilestone> milestones =
                    parseMilestones(section, where, problems);
            if (milestones.isEmpty()) {
                problems.add(where + ".milestones is empty");
                continue;
            }
            final CollectionEntry entry = new CollectionEntry(id, category,
                    section.getString("display", rawId), icon == null ? category.icon() : icon,
                    action, keys, sources, milestones, hidden, hint);
            entries.put(id, entry);
            byCategory.computeIfAbsent(category, ignored -> new ArrayList<>()).add(entry);
            byAction.computeIfAbsent(action, ignored -> new ArrayList<>()).add(entry);
        }
    }

    private List<CollectionMilestone> parseMilestones(final ConfigurationSection section,
                                                      final String where,
                                                      final List<String> problems) {
        final List<CollectionMilestone> milestones = new ArrayList<>();
        final List<Map<?, ?>> raw = section.getMapList("milestones");
        long previous = 0;
        int index = 0;
        for (final Map<?, ?> map : raw) {
            final String at = where + ".milestones[" + index + "]";
            final Object rawAmount = map.get("amount");
            if (!(rawAmount instanceof Number number)) {
                problems.add(at + ".amount is missing or not a number");
                index++;
                continue;
            }
            final long amount = number.longValue();
            if (amount <= 0) {
                problems.add(at + ".amount must be positive");
            } else if (amount <= previous) {
                problems.add(at + ".amount (" + amount + ") must be greater than the previous"
                        + " milestone (" + previous + ")");
            }
            final List<Reward> rewards = new ArrayList<>();
            final Object rawRewards = map.get("rewards");
            if (rawRewards instanceof List<?> list) {
                for (final Object rewardRaw : list) {
                    if (rewardRaw instanceof Map<?, ?> rewardMap) {
                        final Reward reward = reward(rewardMap, at + ".rewards", problems);
                        if (reward != null) {
                            rewards.add(reward);
                        }
                    } else {
                        problems.add(at + ".rewards: entries must be mappings");
                    }
                }
            }
            milestones.add(new CollectionMilestone(index, amount, rewards));
            previous = Math.max(previous, amount);
            index++;
        }
        return milestones;
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
        if (type == RewardType.ITEM) {
            final Material material = material(id, where + ".id", problems);
            if (material == null) {
                return null;
            }
        }
        return new Reward(type, id, amount,
                map.get("display") == null ? "" : String.valueOf(map.get("display")));
    }

    /** Rewards may only point at recipes and Collections that exist. */
    private void validateRewardTargets(final List<String> problems) {
        for (final CollectionEntry entry : entries.values()) {
            for (final CollectionMilestone milestone : entry.milestones()) {
                for (final Reward reward : milestone.rewards()) {
                    if (reward.type() == RewardType.RECIPE && !recipes.containsKey(reward.id())) {
                        problems.add("collections." + entry.id() + " tier " + milestone.tier()
                                + ": unknown recipe '" + reward.id() + "'");
                    }
                    if (reward.type() == RewardType.COLLECTION_TIER
                            && !entries.containsKey(reward.id())) {
                        problems.add("collections." + entry.id() + " tier " + milestone.tier()
                                + ": unknown collection '" + reward.id() + "'");
                    }
                }
            }
        }
        for (final UnlockableRecipe recipe : recipes.values()) {
            final CollectionEntry entry = entries.get(recipe.collectionId());
            if (entry == null) {
                problems.add("recipes." + recipe.id() + ".requires.collection: unknown collection '"
                        + recipe.collectionId() + "'");
            } else if (recipe.tier() > entry.tiers()) {
                problems.add("recipes." + recipe.id() + ".requires.tier: " + entry.id()
                        + " only has " + entry.tiers() + " tiers");
            }
        }
    }

    // ------------------------------------------------------------------
    // lookups
    // ------------------------------------------------------------------

    public boolean enabled() {
        return enabled;
    }

    public int entriesPerPage() {
        return entriesPerPage;
    }

    public boolean announceMilestones() {
        return announceMilestones;
    }

    /** Every entry, in config order. */
    public List<CollectionEntry> all() {
        return List.copyOf(entries.values());
    }

    /** One entry by stable id, or null. */
    public CollectionEntry byId(final String id) {
        return id == null ? null : entries.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Entries of one category, in config order. */
    public List<CollectionEntry> byCategory(final CollectionCategory category) {
        return List.copyOf(byCategory.getOrDefault(category, List.of()));
    }

    /** Entries fed by one action (the hot path when an event arrives). */
    public List<CollectionEntry> byAction(final ProgressAction action) {
        return List.copyOf(byAction.getOrDefault(action, List.of()));
    }

    /** Every Collection-locked recipe. */
    public List<UnlockableRecipe> recipes() {
        return List.copyOf(recipes.values());
    }

    /** One recipe by id, or null. */
    public UnlockableRecipe recipe(final String id) {
        return id == null ? null : recipes.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Categories that actually have entries, in enum order. */
    public List<CollectionCategory> categories() {
        final List<CollectionCategory> out = new ArrayList<>();
        for (final CollectionCategory category : CollectionCategory.values()) {
            if (!byCategory.getOrDefault(category, List.of()).isEmpty()) {
                out.add(category);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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

    private static UnlockableRecipe.Ingredient ingredient(final String raw, final String where,
                                                          final List<String> problems) {
        if (raw == null || raw.isBlank()) {
            problems.add(where + ": empty ingredient");
            return null;
        }
        final String[] parts = raw.split(":");
        final Material material = material(parts[0], where, problems);
        if (material == null) {
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Integer.parseInt(parts[1].trim());
            } catch (final NumberFormatException badNumber) {
                problems.add(where + ": '" + raw + "' has a non-numeric amount");
                return null;
            }
        }
        if (amount <= 0) {
            problems.add(where + ": '" + raw + "' needs a positive amount");
            return null;
        }
        return new UnlockableRecipe.Ingredient(material, amount);
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
