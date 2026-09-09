package com.coremc.core.util;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Shared YAML upgrade-safety: every file-backed catalogue (shop,
 * crates, enchants, themes, ...) loads through here so plugin updates
 * can ship new keys without wiping admin customisations.
 *
 * Behaviour: missing file → copy the bundled default; existing file →
 * merge any NEW bundled keys into it (disk values always win, nothing
 * is ever deleted or overwritten). Idempotent and cheap — safe to
 * call on every load/reload.
 */
public final class YamlFiles {

    private YamlFiles() {
    }

    public static void mergeNewDefaults(final JavaPlugin plugin, final String fileName) {
        final File file = new File(plugin.getDataFolder(), fileName);
        if (!file.isFile()) {
            plugin.saveResource(fileName, false);
            return;
        }
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(plugin.getResource(fileName)), StandardCharsets.UTF_8)) {
            final YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
            disk.setDefaults(YamlConfiguration.loadConfiguration(reader));
            disk.options().copyDefaults(true);
            disk.save(file);
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not merge " + fileName + " defaults: " + exception.getMessage());
        }
    }
}
