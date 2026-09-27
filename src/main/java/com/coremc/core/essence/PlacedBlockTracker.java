package com.coremc.core.essence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** How long a just-broken placement is still reported as player-placed. */
    private static final long REMOVAL_MEMORY_MS = 5_000L;
    /** Upper bound on the removal memory, so a mining rampage cannot grow it. */
    private static final int MAX_REMEMBERED = 512;

    private final Path file;
    private final Logger logger;
    private final Set<String> positions = new HashSet<>();
    private final Map<String, Long> recentlyRemoved = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Long> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

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

    /**
     * True when a player placed a block here, including one broken a
     * moment ago — the order in which MONITOR listeners see a break is
     * not defined, so every listener must get the same answer.
     */
    public boolean wasPlayerPlaced(final String world, final int x, final int y, final int z) {
        final String key = key(world, x, y, z);
        if (positions.contains(key)) {
            return true;
        }
        final Long removedAt = recentlyRemoved.get(key);
        if (removedAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - removedAt > REMOVAL_MEMORY_MS) {
            recentlyRemoved.remove(key);
            return false;
        }
        return true;
    }

    /** Records a player placement. */
    public void add(final String world, final int x, final int y, final int z) {
        recentlyRemoved.remove(key(world, x, y, z));
        if (positions.add(key(world, x, y, z))) {
            persist();
        }
    }

    /** Forgets a position (the placed block was broken). */
    public void remove(final String world, final int x, final int y, final int z) {
        if (positions.remove(key(world, x, y, z))) {
            recentlyRemoved.put(key(world, x, y, z), System.currentTimeMillis());
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
