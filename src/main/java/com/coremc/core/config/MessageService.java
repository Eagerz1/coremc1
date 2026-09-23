package com.coremc.core.config;

import com.coremc.core.util.ColorUtil;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and serves CoreMC messages from messages.yml.
 *
 * All user-facing strings are {@code &} colour-coded, standard Minecraft
 * formatting. The branded prefix lives under the {@code prefix} key and is
 * applied by {@link #sendPrefixed}. Bundled defaults act as a fallback
 * chain, so plugin updates that add message keys work on installs with an
 * older, customised messages.yml.
 */
public final class MessageService {

    private static final String FILE_NAME = "messages.yml";

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration yaml;
    private String prefix = "";

    public MessageService(final JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /** (Re)loads messages.yml from disk, writing defaults first. */
    public void load() {
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);

        try (InputStream stream = plugin.getResource(FILE_NAME)) {
            if (stream != null) {
                final YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                this.yaml.setDefaults(defaults);
            } else {
                plugin.getLogger().warning("Bundled messages.yml resource missing from jar.");
            }
        } catch (final IOException exception) {
            plugin.getLogger().warning("Failed to load bundled messages.yml defaults: " + exception.getMessage());
        }

        this.prefix = ColorUtil.colorize(yaml.getString("prefix", ""));
    }

    /** Raw branded prefix, already colourised. */
    public String prefix() {
        return prefix;
    }

    /**
     * Colourised message for {@code key} with {placeholder} substitution.
     * Missing keys render as a visible error string so misconfiguration is obvious.
     */
    public String get(final String key, final Map<String, String> placeholders) {
        final String raw = yaml.getString(key);
        if (raw == null) {
            return ColorUtil.colorize("&cMissing message: " + key);
        }
        return ColorUtil.colorize(applyPlaceholders(raw, placeholders));
    }

    public String get(final String key) {
        return get(key, Map.of());
    }

    /** Sends a branded message (prefix + message) to a sender. */
    public void sendPrefixed(final CommandSender sender, final String key, final Map<String, String> placeholders) {
        sender.sendMessage(prefix + get(key, placeholders));
    }

    public void sendPrefixed(final CommandSender sender, final String key) {
        sendPrefixed(sender, key, Map.of());
    }

    /** Sends a colourised list of lines (e.g. help text) to a sender. */
    public void sendList(final CommandSender sender, final String key) {
        sendList(sender, key, Map.of());
    }

    /** Sends a colourised list of lines (e.g. help text) to a sender. */
    public void sendList(final CommandSender sender, final String key, final Map<String, String> placeholders) {
        final List<String> raw = yaml.getStringList(key);
        final List<String> lines = new ArrayList<>(raw.size());
        for (final String line : raw) {
            lines.add(ColorUtil.colorize(applyPlaceholders(line, placeholders)));
        }
        sender.sendMessage(lines.toArray(new String[0]));
    }

    private String applyPlaceholders(final String raw, final Map<String, String> placeholders) {
        String result = raw;
        for (final Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
