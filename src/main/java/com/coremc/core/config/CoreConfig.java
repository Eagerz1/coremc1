package com.coremc.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Typed access to CoreMC's config.yml.
 *
 * Values that may need balancing are read through here rather than
 * scattered around the codebase; a missing or invalid value falls back
 * to a sane default (with a log line) instead of breaking the plugin.
 */
public final class CoreConfig {

    /** One starter-chest entry: a material and how many of it to give. */
    public record ChestItem(Material material, int amount) {
    }

    private static final int MIN_SPACING = 64;
    private static final int DEFAULT_SPACING = 200;
    private static final int DEFAULT_BORDER_SIZE = 100;
    private static final int MIN_BORDER_SIZE = 10;
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;
    private static final String WORLD_NAME_PATTERN = "[a-zA-Z0-9_-]+";

    private final JavaPlugin plugin;

    private String islandWorldName = "coremc_islands";
    private int islandYLevel = 64;
    private int islandSpacing = DEFAULT_SPACING;
    private int islandBorderSize = DEFAULT_BORDER_SIZE;
    private boolean borderVisual = true;
    private String islandSchematic = "default";
    private long deleteConfirmSeconds = 30L;
    private long inviteExpirySeconds = 300L;
    private final List<ChestItem> chestItems = new ArrayList<>();

    public CoreConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads config.yml and re-reads every setting. */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        final FileConfiguration config = plugin.getConfig();

        final String world = config.getString("island.world", islandWorldName);
        if (world == null || !world.matches(WORLD_NAME_PATTERN)) {
            plugin.getLogger().warning("island.world '" + world + "' is not a valid world name, "
                    + "using '" + islandWorldName + "'.");
        } else {
            this.islandWorldName = world;
        }

        this.islandYLevel = clamp(config.getInt("island.y-level", 64), MIN_Y, MAX_Y, "island.y-level");

        int spacing = config.getInt("island.spacing", DEFAULT_SPACING);
        if (spacing < MIN_SPACING) {
            plugin.getLogger().warning("island.spacing " + spacing + " is below " + MIN_SPACING
                    + ", using " + DEFAULT_SPACING + ".");
            spacing = DEFAULT_SPACING;
        }
        this.islandSpacing = spacing;

        int border = config.getInt("island.border-size", DEFAULT_BORDER_SIZE);
        if (border > this.islandSpacing) {
            plugin.getLogger().warning("island.border-size " + border + " exceeds island.spacing "
                    + this.islandSpacing + " (islands would overlap), clamping.");
            border = this.islandSpacing;
        }
        if (border < MIN_BORDER_SIZE) {
            plugin.getLogger().warning("island.border-size " + border + " is below " + MIN_BORDER_SIZE
                    + ", using " + DEFAULT_BORDER_SIZE + ".");
            border = DEFAULT_BORDER_SIZE;
        }
        this.islandBorderSize = border - (border % 2);

        this.borderVisual = config.getBoolean("island.border-visual", true);

        final String schematic = config.getString("island.schematic", islandSchematic);
        if (schematic == null || !schematic.matches(WORLD_NAME_PATTERN)) {
            plugin.getLogger().warning("island.schematic '" + schematic + "' is not a valid name, "
                    + "using '" + islandSchematic + "'.");
        } else {
            this.islandSchematic = schematic;
        }

        this.deleteConfirmSeconds = Math.max(5L, config.getLong("island.delete-confirm-seconds", 30L));
        this.inviteExpirySeconds = Math.max(15L, config.getLong("island.invite-expiry-seconds", 300L));

        this.chestItems.clear();
        for (final String raw : config.getStringList("island.chest-items")) {
            final ChestItem item = parseChestItem(raw);
            if (item == null) {
                plugin.getLogger().warning("Skipping invalid island.chest-items entry '" + raw + "'.");
            } else {
                this.chestItems.add(item);
            }
        }
    }

    private ChestItem parseChestItem(final String raw) {
        if (raw == null) {
            return null;
        }
        final String[] parts = raw.split(":");
        if (parts.length != 2) {
            return null;
        }
        final Material material = Material.matchMaterial(parts[0].trim());
        if (material == null || !material.isItem()) {
            return null;
        }
        try {
            final int amount = Integer.parseInt(parts[1].trim());
            if (amount < 1 || amount > material.getMaxStackSize()) {
                return null;
            }
            return new ChestItem(material, amount);
        } catch (final NumberFormatException exception) {
            return null;
        }
    }

    private int clamp(final int value, final int min, final int max, final String key) {
        if (value < min || value > max) {
            plugin.getLogger().warning(key + " " + value + " is outside " + min + ".." + max + ", using default.");
            return 64;
        }
        return value;
    }

    public String islandWorldName() {
        return islandWorldName;
    }

    public int islandYLevel() {
        return islandYLevel;
    }

    public int islandSpacing() {
        return islandSpacing;
    }

    public int islandBorderSize() {
        return islandBorderSize;
    }

    public boolean borderVisual() {
        return borderVisual;
    }

    public String islandSchematic() {
        return islandSchematic;
    }

    public long deleteConfirmSeconds() {
        return deleteConfirmSeconds;
    }

    public long inviteExpirySeconds() {
        return inviteExpirySeconds;
    }

    public List<ChestItem> chestItems() {
        return Collections.unmodifiableList(chestItems);
    }
}
