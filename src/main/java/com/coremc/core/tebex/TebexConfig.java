package com.coremc.core.tebex;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads the Tebex webstore settings from {@code tebex.yml}: the
 * Plugin API secret key (creator panel: Integrations > Plugin API)
 * and an overridable API base URL. The integration stays off until
 * the owner fills in the key.
 */
public final class TebexConfig {

    private static final String FILE_NAME = "tebex.yml";
    private static final String DEFAULT_API_BASE = "https://plugin.tebex.io";

    private final JavaPlugin plugin;
    private boolean enabled;
    private String secretKey;
    private String apiBase;

    public TebexConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
        this.enabled = false;
        this.secretKey = "";
        this.apiBase = DEFAULT_API_BASE;
    }

    /**
     * Directly configured instance (tests and programmatic setup):
     * active as soon as a secret key is present.
     */
    public TebexConfig(final String secretKey, final String apiBase) {
        this.plugin = null;
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.apiBase = apiBase == null || apiBase.isBlank()
                ? DEFAULT_API_BASE : apiBase.trim();
        this.enabled = !this.secretKey.isEmpty();
    }

    /** Extracts the default tebex.yml on first run, then parses it. */
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
        final String key = yaml.getString("secret-key", "");
        final String base = yaml.getString("api-base", DEFAULT_API_BASE);
        this.secretKey = key == null ? "" : key.trim();
        this.apiBase = (base == null || base.isBlank()) ? DEFAULT_API_BASE : base.trim();
        // only meaningful with a filled-in key: no half-configured states
        this.enabled = yaml.getBoolean("enabled", false) && !this.secretKey.isEmpty();
        if (this.apiBase.endsWith("/")) {
            this.apiBase = this.apiBase.substring(0, this.apiBase.length() - 1);
        }
    }

    /** True when the integration is configured and active. */
    public boolean enabled() {
        return enabled;
    }

    /** The Plugin API secret key (never logged). */
    public String secretKey() {
        return secretKey;
    }

    /** Plugin API base URL (default {@code https://plugin.tebex.io}). */
    public String apiBase() {
        return apiBase;
    }
}
