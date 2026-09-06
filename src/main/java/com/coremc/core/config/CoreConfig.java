package com.coremc.core.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Typed access to CoreMC's config.yml.
 *
 * Values that may need balancing are read through here rather than
 * scattered around the codebase, and a missing/invalid value falls
 * back to a sane default instead of breaking the plugin.
 */
public final class CoreConfig {

    private static final long MIN_AUTOSAVE_SECONDS = 30L;
    private static final long DEFAULT_AUTOSAVE_SECONDS = 300L;

    private final JavaPlugin plugin;

    private long autosaveSeconds = DEFAULT_AUTOSAVE_SECONDS;
    private boolean firstJoinMessage = true;

    public CoreConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads config.yml and re-reads every setting. */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        final FileConfiguration config = plugin.getConfig();

        final long configuredAutosave = config.getLong("player-data.autosave-seconds", DEFAULT_AUTOSAVE_SECONDS);
        if (configuredAutosave < MIN_AUTOSAVE_SECONDS) {
            plugin.getLogger()
                    .warning("player-data.autosave-seconds is below " + MIN_AUTOSAVE_SECONDS
                            + "s (" + configuredAutosave + "s), clamping to " + DEFAULT_AUTOSAVE_SECONDS + "s.");
            this.autosaveSeconds = DEFAULT_AUTOSAVE_SECONDS;
        } else {
            this.autosaveSeconds = configuredAutosave;
        }

        this.firstJoinMessage = config.getBoolean("welcome.first-join-message", true);
    }

    /** Seconds between automatic flushes of dirty player profiles. */
    public long autosaveSeconds() {
        return autosaveSeconds;
    }

    /** Whether the first-join welcome message is enabled. */
    public boolean firstJoinMessage() {
        return firstJoinMessage;
    }
}
