package com.coremc.core.island;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Island points — the currency of the island top leaderboards.
 *
 * <p>How islands earn points:</p>
 * <ul>
 *   <li>actions: every block broken or placed on the island —
 *       {@link #ACTION_POINTS} each,</li>
 *   <li>playing: {@link #PLAY_POINTS} per 5 minutes online,</li>
 *   <li>island upgrades: {@link #UPGRADE_POINTS} per upgrade
 *       purchased,</li>
 *   <li>island core levels (coming soon): {@link #CORE_LEVEL_POINTS}
 *       per level.</li>
 * </ul>
 *
 * <p>Points live in memory keyed by island id and flush to
 * {@code plugins/CoreMC/island-points.yml} every minute when dirty
 * (block actions are far too frequent for write-through) plus on
 * disable. Deleted islands drop their points.</p>
 */
public final class IslandPointsService {

    /** Points per block broken or placed on the island. */
    public static final double ACTION_POINTS = 0.2;
    /** Points per 5 minutes of play time. */
    public static final double PLAY_POINTS = 1.0;
    /** Points per island upgrade purchased. */
    public static final double UPGRADE_POINTS = 500.0;
    /** Points per island core level (the core arrives soon). */
    public static final double CORE_LEVEL_POINTS = 5000.0;

    private final Path file;
    private final Map<UUID, Double> points = new LinkedHashMap<>();
    private boolean dirty;

    public IslandPointsService(final Path file) {
        this.file = file;
    }

    /** Loads the persisted points (missing file = fresh start). */
    public void load() throws IOException {
        points.clear();
        dirty = false;
        if (!Files.isRegularFile(file)) {
            return;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IOException("corrupt island-points file: " + exception.getMessage(), exception);
        }
        for (final String key : yaml.getKeys(false)) {
            try {
                points.put(UUID.fromString(key), round(yaml.getDouble(key)));
            } catch (final IllegalArgumentException exception) {
                // skip bad lines, keep the rest
            }
        }
    }

    /** The island's current points (0 when never earned). */
    public double points(final Island island) {
        return points.getOrDefault(island.id(), 0.0);
    }

    /** Adds points (rounded to centipoints) without persisting yet. */
    public void add(final Island island, final double amount) {
        points.merge(island.id(), round(amount), (a, b) -> round(a + b));
        dirty = true;
    }

    /** Drops an island's points (island deleted). */
    public void remove(final Island island) {
        if (points.remove(island.id()) != null) {
            dirty = true;
        }
    }

    /** True when unsaved changes exist. */
    public boolean dirty() {
        return dirty;
    }

    /** Writes the points file when dirty; returns true when it wrote. */
    public boolean flush() throws IOException {
        if (!dirty) {
            return false;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, Double> entry : points.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        dirty = false;
        return true;
    }

    /** Rounds to two decimals so 0.2 increments never drift. */
    static double round(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Display format: {@code 0.2}, {@code 500}, {@code 1,234.5}. */
    public static String format(final double value) {
        final double rounded = round(value);
        if (rounded == Math.floor(rounded)) {
            return String.format(Locale.US, "%,.0f", rounded);
        }
        return String.format(Locale.US, "%,.1f", rounded);
    }
}
