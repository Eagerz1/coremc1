package com.coremc.core.util;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Shared YAML upgrade-safety: every file-backed catalogue (shop,
 * crates, enchants, themes, ...) loads through here so plugin updates
 * can ship new keys without wiping admin customisations.
 *
 * Behaviour: missing file → copy the bundled default; existing file →
 * merge any NEW bundled keys into it (disk values always win, nothing
 * is ever deleted or overwritten). Idempotent and cheap — safe to call
 * on every load/reload.
 *
 * The merge runs on RAW SnakeYAML maps (see {@link RawYaml}) so keys
 * containing dots — enchant ids such as {@code miner.treasure-miner}
 * — stay literal and are never exploded into nested sections.
 */
public final class YamlFiles {

    private YamlFiles() {
    }

    public static void mergeNewDefaults(final JavaPlugin plugin, final String fileName) {
        RawYaml.mergeNewDefaults(plugin, fileName);
    }
}
