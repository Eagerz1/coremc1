package com.coremc.core.essence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Remembers where players placed blocks that award Mining Essence, so
 * breaking a placed block never pays (no place-mine loops). Only
 * eligible materials are tracked, which keeps the set tiny even on
 * busy islands. Persisted in {@code placed-blocks.yml}, atomic like
 * every other CoreMC store.
 */
public final class PlacedBlockTracker {

    private final Path file;
    private final Logger logger;
    private final Set<String> positions = new HashSet<>();

    public PlacedBlockTracker(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Loads the persisted positions (call once on enable). */
    public void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            final YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            positions.clear();
            positions.addAll(yaml.getStringList("placed"));
        } catch (final Exception exception) {
            logger.warning("Corrupt placed-blocks file " + file + " — starting fresh: "
                    + exception.getMessage());
        }
    }

    /** True when a player placed a block at this position (and it should not pay essence). */
    public boolean isPlayerPlaced(final String world, final int x, final int y, final int z) {
        return positions.contains(key(world, x, y, z));
    }

    /** Records a player placement. */
    public void add(final String world, final int x, final int y, final int z) {
        if (positions.add(key(world, x, y, z))) {
            persist();
        }
    }

    /** Forgets a position (the placed block was broken). */
    public void remove(final String world, final int x, final int y, final int z) {
        if (positions.remove(key(world, x, y, z))) {
            persist();
        }
    }

    private static String key(final String world, final int x, final int y, final int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    private void persist() {
        try {
            final YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("placed", new java.util.ArrayList<>(positions));
            Files.createDirectories(file.getParent());
            final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException exception) {
            logger.severe("Could not save placed-blocks: " + exception.getMessage());
        }
    }
}
