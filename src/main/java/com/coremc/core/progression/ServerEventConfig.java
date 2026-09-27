package com.coremc.core.progression;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.boss.BarColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Configuration for hourly server-wide events. */
public final class ServerEventConfig {

    public static final List<String> REQUIRED_EVENTS = List.of(
            "mining-frenzy", "slayer-frenzy", "fishing-rush", "generator-overdrive",
            "2x-island-xp", "2x-omnitool-progress", "role-xp-rush", "core-surge",
            "farming-festival");
    private static final String FILE_NAME = "events.yml";

    public record EventDef(String id, String name, BarColor color, List<String> lore,
                           Map<String, Double> effects) {
        public double effect(final String key, final double fallback) {
            return effects.getOrDefault(normalise(key), fallback);
        }
    }

    private final JavaPlugin plugin;
    private boolean enabled = true;
    private long intervalMillis = 60L * 60L * 1000L;
    private long durationMillis = 15L * 60L * 1000L;
    private boolean avoidImmediateRepeats = true;
    private final Map<String, EventDef> events = new LinkedHashMap<>();

    public ServerEventConfig(final JavaPlugin plugin) {
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
        this.intervalMillis = Math.max(1L, yaml.getLong("settings.interval-minutes", 60L)) * 60_000L;
        this.durationMillis = Math.max(1L, yaml.getLong("settings.duration-minutes", 15L)) * 60_000L;
        if (durationMillis >= intervalMillis) {
            problems.add("settings.duration-minutes must be shorter than settings.interval-minutes");
        }
        this.avoidImmediateRepeats = yaml.getBoolean("settings.avoid-immediate-repeats", true);

        final Map<String, EventDef> parsed = new LinkedHashMap<>();
        final ConfigurationSection root = yaml.getConfigurationSection("events");
        if (root == null) {
            problems.add("missing 'events' mapping");
        } else {
            for (final String rawId : root.getKeys(false)) {
                final String id = normalise(rawId);
                final ConfigurationSection section = root.getConfigurationSection(rawId);
                if (section == null) {
                    problems.add("events." + rawId + " is not a mapping");
                    continue;
                }
                BarColor color;
                try {
                    color = BarColor.valueOf(section.getString("bossbar-color", "BLUE").toUpperCase(Locale.ROOT));
                } catch (final IllegalArgumentException exception) {
                    problems.add("events." + rawId + ": bad bossbar-color");
                    color = BarColor.BLUE;
                }
                final Map<String, Double> effects = new LinkedHashMap<>();
                final ConfigurationSection effectSection = section.getConfigurationSection("effects");
                if (effectSection != null) {
                    for (final String key : effectSection.getKeys(false)) {
                        final double value = effectSection.getDouble(key, 1.0D);
                        if (value < 0.0D) {
                            problems.add("events." + rawId + ".effects." + key + " cannot be negative");
                        }
                        effects.put(normalise(key), value);
                    }
                }
                parsed.put(id, new EventDef(id, section.getString("name", title(id)), color,
                        section.getStringList("lore"), Map.copyOf(effects)));
            }
        }
        for (final String required : REQUIRED_EVENTS) {
            if (!parsed.containsKey(required)) {
                problems.add("missing required event '" + required + "'");
            }
        }
        if (!problems.isEmpty()) {
            enabled = false;
            throw new IllegalArgumentException("broken " + FILE_NAME + ": " + String.join("; ", problems));
        }
        events.clear();
        events.putAll(parsed);
        enabled = true;
    }

    public static ServerEventConfig disabled() {
        final ServerEventConfig config = new ServerEventConfig(null);
        config.enabled = false;
        return config;
    }

    public boolean enabled() {
        return enabled;
    }

    public long intervalMillis() {
        return intervalMillis;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public boolean avoidImmediateRepeats() {
        return avoidImmediateRepeats;
    }

    public List<EventDef> events() {
        return List.copyOf(events.values());
    }

    public EventDef event(final String id) {
        return events.get(normalise(id));
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
