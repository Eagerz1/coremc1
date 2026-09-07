package com.coremc.core.island;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and serves island themes from {@code themes.yml}.
 *
 * Themes are fully data-driven: adding a new section to themes.yml and
 * restarting extends the theme selection with no code changes (the
 * "supported through theme files" requirement). Invalid materials or
 * malformed decoration entries are skipped with a warning — one bad
 * theme never breaks the whole system.
 */
public final class ThemeService {

    /** Fallback theme key used for legacy islands and unknown input. */
    public static final String DEFAULT_THEME = "plains";

    private final JavaPlugin plugin;
    private final Map<String, IslandTheme> themes = new LinkedHashMap<>();

    public ThemeService(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads themes.yml. Returns the number of valid themes loaded. */
    public int load() {
        themes.clear();
        plugin.saveResource("themes.yml", true);
        final YamlConfiguration config =
                YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(), "themes.yml"));
        final ConfigurationSection section = config.getConfigurationSection("themes");
        if (section == null) {
            plugin.getLogger().warning("themes.yml has no 'themes' section — theme selection disabled.");
            return 0;
        }
        for (final String key : section.getKeys(false)) {
            final ConfigurationSection def = section.getConfigurationSection(key);
            if (def == null) {
                continue;
            }
            final String cleanKey = key.toLowerCase(Locale.ROOT);
            final Material icon = material(def.getString("icon"), Material.GRASS_BLOCK, cleanKey);
            final Material top = material(def.getString("top"), Material.GRASS_BLOCK, cleanKey);
            final Material under = material(def.getString("under"), Material.DIRT, cleanKey);
            final List<String> decorations = new ArrayList<>();
            for (final String entry : def.getStringList("decorations")) {
                if (parseDecoration(entry) != null) {
                    decorations.add(entry);
                } else {
                    plugin.getLogger()
                            .warning("Theme '" + cleanKey + "' has malformed decoration '" + entry + "'; skipped.");
                }
            }
            final List<String> contents = new ArrayList<>();
            for (final String entry : def.getStringList("chest-contents")) {
                if (parseContent(entry) != null) {
                    contents.add(entry);
                } else {
                    plugin.getLogger()
                            .warning("Theme '" + cleanKey + "' has malformed chest content '" + entry + "'; skipped.");
                }
            }
            themes.put(cleanKey, new IslandTheme(
                    cleanKey,
                    def.getString("display", "&f" + cleanKey),
                    icon,
                    def.getStringList("description"),
                    top,
                    under,
                    def.getBoolean("tree", false),
                    List.copyOf(decorations),
                    List.copyOf(contents)));
        }
        if (!themes.containsKey(DEFAULT_THEME)) {
            plugin.getLogger().warning("themes.yml has no '" + DEFAULT_THEME + "' theme — new islands may fail.");
        }
        return themes.size();
    }

    /** All loaded themes in file order. */
    public List<IslandTheme> all() {
        return new ArrayList<>(themes.values());
    }

    /** The theme for {@code key}, or empty if unknown. */
    public Optional<IslandTheme> theme(final String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(themes.get(key.toLowerCase(Locale.ROOT)));
    }

    /** The default theme (empty only if themes.yml is broken). */
    public Optional<IslandTheme> defaultTheme() {
        return theme(DEFAULT_THEME);
    }

    /** All theme keys (tab-complete). */
    public List<String> keys() {
        return new ArrayList<>(themes.keySet());
    }

    private Material material(final String name, final Material fallback, final String key) {
        final Material material = Material.matchMaterial(name == null ? "" : name);
        if (material == null) {
            plugin.getLogger().warning("Theme '" + key + "' references unknown material '" + name + "'.");
            return fallback;
        }
        return material;
    }

    /** Parses "dx,dy,dz=MATERIAL"; returns int[]{dx,dy,dz} + material, or null when malformed. */
    public static Object[] parseDecoration(final String entry) {
        final String[] halves = entry.split("=");
        if (halves.length != 2) {
            return null;
        }
        final Material material = Material.matchMaterial(halves[1].trim());
        if (material == null) {
            return null;
        }
        final String[] coords = halves[0].split(",");
        if (coords.length != 3) {
            return null;
        }
        try {
            final int dx = Integer.parseInt(coords[0].trim());
            final int dy = Integer.parseInt(coords[1].trim());
            final int dz = Integer.parseInt(coords[2].trim());
            return new Object[] {dx, dy, dz, material};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses "MATERIAL:count"; returns Object[]{Material, Integer}, or null when malformed. */
    public static Object[] parseContent(final String entry) {
        final String[] halves = entry.split(":");
        if (halves.length != 2) {
            return null;
        }
        final Material material = Material.matchMaterial(halves[0].trim());
        if (material == null) {
            return null;
        }
        try {
            final int count = Math.max(1, Math.min(64, Integer.parseInt(halves[1].trim())));
            return new Object[] {material, count};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
