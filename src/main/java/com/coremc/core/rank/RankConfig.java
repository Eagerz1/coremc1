package com.coremc.core.rank;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The rank ladder from ranks.yml, ordered worst to best. The whole
 * file is validated at startup; anything broken disables the rank
 * system with one loud log line (no ranks, multiplier 1.0, no
 * payouts) rather than half-working.
 */
public final class RankConfig {

    private static final String FILE_NAME = "ranks.yml";

    /** One rung of the ladder. */
    public record RankDef(String id, String name, double price, double moneyMultiplier,
                          double seasonMoney, int riverKeys,
                          boolean chatColor, boolean gradients, boolean bold) {

        public String perksSummary() {
            final List<String> perks = new ArrayList<>();
            perks.add("/fly");
            perks.add("/echest");
            if (chatColor) {
                perks.add("chat colours");
            }
            if (gradients) {
                perks.add("gradients");
            }
            if (bold) {
                perks.add("bold");
            }
            return String.join(", ", perks);
        }
    }

    private final JavaPlugin plugin;
    private boolean enabled = true;
    private List<RankDef> ranks = List.of();

    public RankConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Extracts the default ranks.yml on first run, then parses and validates. */
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
        final ConfigurationSection root = yaml.getConfigurationSection("ranks");
        if (root == null) {
            problems.add("missing 'ranks' mapping");
        }
        final List<RankDef> parsed = new ArrayList<>();
        if (root != null) {
            for (final String id : root.getKeys(false)) {
                final RankDef def = parseRank(id, root.getConfigurationSection(id), problems);
                if (def != null) {
                    parsed.add(def);
                }
            }
        }
        if (parsed.size() < 1) {
            problems.add("needs at least one rank");
        }
        // The ladder must be strictly better as it goes up: every
        // multiplier, payout and key count has to beat the rank below.
        for (int i = 1; i < parsed.size(); i++) {
            final RankDef below = parsed.get(i - 1);
            final RankDef above = parsed.get(i);
            if (above.moneyMultiplier() < below.moneyMultiplier()) {
                problems.add("rank '" + above.id() + "': money-multiplier must not fall below '"
                        + below.id() + "'");
            }
            if (above.seasonMoney() < below.seasonMoney()) {
                problems.add("rank '" + above.id() + "': season-money must not fall below '"
                        + below.id() + "'");
            }
            if (above.riverKeys() < below.riverKeys()) {
                problems.add("rank '" + above.id() + "': river-keys must not fall below '"
                        + below.id() + "'");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("broken " + FILE_NAME + ": "
                    + String.join("; ", problems));
        }
        this.ranks = List.copyOf(parsed);
        this.enabled = true;
    }

    private RankDef parseRank(final String id, final ConfigurationSection section,
                              final List<String> problems) {
        if (section == null) {
            problems.add("rank '" + id + "' is not a mapping");
            return null;
        }
        final String name = section.getString("name", id);
        if (name == null || name.isBlank()) {
            problems.add("rank '" + id + "': empty name");
        }
        final double price = section.getDouble("price", 0);
        if (price < 0) {
            problems.add("rank '" + id + "': price cannot be negative");
        }
        final double multiplier = section.getDouble("money-multiplier", 1.0);
        if (multiplier < 1.0) {
            problems.add("rank '" + id + "': money-multiplier must be at least 1");
        }
        final double seasonMoney = section.getDouble("season-money", 0);
        if (seasonMoney < 0) {
            problems.add("rank '" + id + "': season-money cannot be negative");
        }
        final int keys = section.getInt("river-keys", 0);
        if (keys < 0) {
            problems.add("rank '" + id + "': river-keys cannot be negative");
        }
        final ConfigurationSection perks = section.getConfigurationSection("perks");
        return new RankDef(id, name, price, multiplier, seasonMoney, keys,
                perks != null && perks.getBoolean("chat-color", false),
                perks != null && perks.getBoolean("gradients", false),
                perks != null && perks.getBoolean("bold", false));
    }

    // ------------------------------------------------------------------
    // Fallback for a broken config: ranks disabled, no perks.
    // ------------------------------------------------------------------

    /** A disabled config: no ranks, no perks, multiplier always 1.0. */
    public static RankConfig disabled() {
        final RankConfig config = new RankConfig(null);
        config.enabled = false;
        config.ranks = List.of();
        return config;
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    public boolean enabled() {
        return enabled;
    }

    /** The ladder, worst first. */
    public List<RankDef> ranks() {
        return ranks;
    }

    /** Rank definition by id, or null. */
    public RankDef byId(final String id) {
        for (final RankDef rank : ranks) {
            if (rank.id().equalsIgnoreCase(id)) {
                return rank;
            }
        }
        return null;
    }

    /** Index of a rank on the ladder (-1 when unknown). */
    public int indexOf(final RankDef rank) {
        for (int i = 0; i < ranks.size(); i++) {
            if (ranks.get(i).id().equals(rank.id())) {
                return i;
            }
        }
        return -1;
    }

    /** The rank above the given one, or null when it is the top. */
    public RankDef nextOf(final RankDef rank) {
        final int index = indexOf(rank);
        if (index < 0 || index + 1 >= ranks.size()) {
            return null;
        }
        return ranks.get(index + 1);
    }

    /** The first (cheapest) rank — what /rank buy starts with. */
    public RankDef first() {
        return ranks.isEmpty() ? null : ranks.get(0);
    }
}
