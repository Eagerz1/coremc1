package com.coremc.core.guide;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Typed loader for the polished /help guide GUI. */
public final class HelpConfig {

    public static final String FILE_NAME = "help.yml";

    public record Category(String id, String title, Material icon, List<String> lines,
                           String shortcut, String pluginCommand) {
    }

    public record CommandEntry(String group, String display, String pluginCommand, String description) {
    }

    private final JavaPlugin plugin;
    private boolean enabled = true;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final List<CommandEntry> commands = new ArrayList<>();

    public HelpConfig(final JavaPlugin plugin) {
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
        final Map<String, Category> parsed = new LinkedHashMap<>();
        final ConfigurationSection root = yaml.getConfigurationSection("categories");
        if (root == null) {
            problems.add("missing categories mapping");
        } else {
            for (final String rawId : root.getKeys(false)) {
                final String id = normalise(rawId);
                final ConfigurationSection section = root.getConfigurationSection(rawId);
                if (section == null) {
                    problems.add("categories." + rawId + " is not a mapping");
                    continue;
                }
                Material icon = Material.BOOK;
                try {
                    icon = Material.valueOf(section.getString("icon", "BOOK").toUpperCase(Locale.ROOT));
                } catch (final IllegalArgumentException exception) {
                    problems.add("categories." + rawId + ".icon is invalid");
                }
                parsed.put(id, new Category(id, section.getString("title", title(id)), icon,
                        section.getStringList("lines"), cleanShortcut(section.getString("shortcut", "")),
                        normalise(section.getString("plugin-command", ""))));
            }
        }
        if (!parsed.containsKey("getting-started")) {
            problems.add("missing required category getting-started");
        }
        if (!parsed.containsKey("commands")) {
            problems.add("missing required category commands");
        }
        final List<CommandEntry> parsedCommands = new ArrayList<>();
        final ConfigurationSection commandRoot = yaml.getConfigurationSection("commands");
        if (commandRoot != null) {
            for (final String group : commandRoot.getKeys(false)) {
                final ConfigurationSection groupSection = commandRoot.getConfigurationSection(group);
                if (groupSection == null) {
                    continue;
                }
                for (final String key : groupSection.getKeys(false)) {
                    final ConfigurationSection entry = groupSection.getConfigurationSection(key);
                    if (entry == null) {
                        continue;
                    }
                    parsedCommands.add(new CommandEntry(title(group),
                            entry.getString("display", "/" + key),
                            normalise(entry.getString("plugin-command", key)),
                            entry.getString("description", "")));
                }
            }
        }
        if (parsedCommands.isEmpty()) {
            problems.add("commands page needs at least one command entry");
        }
        if (!problems.isEmpty()) {
            enabled = false;
            throw new IllegalArgumentException("broken " + FILE_NAME + ": " + String.join("; ", problems));
        }
        categories.clear();
        categories.putAll(parsed);
        commands.clear();
        commands.addAll(parsedCommands);
        enabled = true;
    }

    public static HelpConfig disabled() {
        final HelpConfig config = new HelpConfig(null);
        config.enabled = false;
        return config;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Category> categories() {
        return List.copyOf(categories.values());
    }

    public Category category(final String id) {
        return categories.get(normalise(id));
    }

    public List<CommandEntry> commands() {
        return List.copyOf(commands);
    }

    static String normalise(final String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String cleanShortcut(final String value) {
        final String trimmed = value == null ? "" : value.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    static String title(final String id) {
        final String[] parts = normalise(id).replace('-', ' ').split(" ");
        final StringBuilder out = new StringBuilder();
        for (final String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
