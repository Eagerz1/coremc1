package com.coremc.core.config;

import com.coremc.core.util.ColorUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and serves CoreMC messages from messages.yml.
 *
 * All user-facing strings are '&amp;' colour-coded, standard Minecraft
 * formatting. The branded prefix lives under the {@code prefix} key and
 * is applied by the {@code prefixed*} methods.
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

    /** Sends an unbranded message line to a sender. */
    public void send(final CommandSender sender, final String key, final Map<String, String> placeholders) {
        sender.sendMessage(get(key, placeholders));
    }

    /** Sends a branded message ("prefix + message") to a sender. */
    public void sendPrefixed(final CommandSender sender, final String key, final Map<String, String> placeholders) {
        sender.sendMessage(prefix + get(key, placeholders));
    }

    /** Colourised list of lines for {@code key} with placeholder substitution. */
    public List<String> getList(final String key, final Map<String, String> placeholders) {
        final List<String> raw = yaml.getStringList(key);
        final List<String> lines = new ArrayList<>(raw.size());
        for (final String line : raw) {
            lines.add(ColorUtil.colorize(applyPlaceholders(line, placeholders)));
        }
        return lines;
    }

    /** Sends a list of message to a sender. */
    public void sendList(final CommandSender sender, final String key, final Map<String, String> placeholders) {
        for (final String line : getList(key, placeholders)) {
            sender.sendMessage(line);
        }
    }

    private String applyPlaceholders(final String raw, final Map<String, String> placeholders) {
        String result = raw;
        for (final Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /** Convenience for building placeholder maps fluently. */
    public static Map<String, String> placeholders(final String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders must be given as key/value pairs");
        }
        final Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
