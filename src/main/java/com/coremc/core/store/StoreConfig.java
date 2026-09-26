package com.coremc.core.store;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads {@code store.yml} — the small store-wide settings that are
 * not part of a specific crate/lootbox/bundle: the Credits-per-Euro
 * display line and the island-top season Credits multiplier
 * (ISLAND_MILESTONE Credits = gift-card euros × multiplier). No
 * balance value is hardcoded anywhere.
 */
public final class StoreConfig {

    private static final String FILE_NAME = "store.yml";

    private final JavaPlugin plugin;
    private long creditsPerEuro = 100;
    private double islandTopCreditMultiplier = 5.0;

    public StoreConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Extracts the default store.yml on first run, then parses and validates. */
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

    /** Parses and validates a YAML document (also used by the tests). */
    public void parse(final YamlConfiguration yaml) {
        final long perEuro = yaml.getLong("credits.per-euro", 100);
        if (perEuro <= 0) {
            throw new IllegalArgumentException("broken " + FILE_NAME
                    + ": credits.per-euro must be > 0");
        }
        final double multiplier = yaml.getDouble("island-top-credit-multiplier", 5.0);
        if (multiplier < 0 || !Double.isFinite(multiplier)) {
            throw new IllegalArgumentException("broken " + FILE_NAME
                    + ": island-top-credit-multiplier must be >= 0");
        }
        this.creditsPerEuro = perEuro;
        this.islandTopCreditMultiplier = multiplier;
    }

    /** How many Credits equal one Euro (display: 100 ᴄʀᴇᴅɪᴛs = €1). */
    public long creditsPerEuro() {
        return creditsPerEuro;
    }

    /** ISLAND_MILESTONE Credits per gift-card euro on season payouts. */
    public double islandTopCreditMultiplier() {
        return islandTopCreditMultiplier;
    }
}
