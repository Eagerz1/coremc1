package com.coremc.core.progress.reward;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Safe storage for rewards that could not be handed over yet
 * ({@code pending-rewards.yml}).
 *
 * <p>Atomic writes, one section per player, unusable entries skipped
 * with a warning instead of losing the rest — the same policy as every
 * other CoreMC store. A reward parked here is never lost and never
 * delivered twice: delivery removes the record by its unique id and
 * persists immediately.</p>
 */
public final class PendingRewardStore {

    private final Path file;
    private final Logger logger;
    private final Map<UUID, List<PendingReward>> pending = new LinkedHashMap<>();

    public PendingRewardStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Loads the file (call once on enable). */
    public void load() {
        pending.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            final YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            final var root = yaml.getConfigurationSection("pending");
            if (root == null) {
                return;
            }
            for (final String rawUuid : root.getKeys(false)) {
                final UUID player;
                try {
                    player = UUID.fromString(rawUuid);
                } catch (final IllegalArgumentException badId) {
                    warn("skipping pending rewards for invalid uuid " + rawUuid);
                    continue;
                }
                final List<PendingReward> list = new ArrayList<>();
                for (final Object raw : root.getMapList(rawUuid)) {
                    if (raw instanceof Map<?, ?> map) {
                        final PendingReward reward = PendingReward.fromMap(player, map);
                        if (reward == null) {
                            warn("skipping unreadable pending reward for " + player);
                        } else {
                            list.add(reward);
                        }
                    }
                }
                if (!list.isEmpty()) {
                    pending.put(player, list);
                }
            }
        } catch (final Exception failure) {
            warn("corrupt pending-rewards file " + file + " — starting fresh: "
                    + failure.getMessage());
            pending.clear();
        }
    }

    /** Everything waiting for a player (never null). */
    public List<PendingReward> of(final UUID player) {
        return List.copyOf(pending.getOrDefault(player, List.of()));
    }

    /** How many rewards are waiting. */
    public int count(final UUID player) {
        return pending.getOrDefault(player, List.of()).size();
    }

    /** Parks a reward and persists immediately. */
    public PendingReward add(final UUID player, final Reward reward, final String source) {
        final PendingReward record = PendingReward.of(player, reward, source);
        pending.computeIfAbsent(player, ignored -> new ArrayList<>()).add(record);
        persist();
        return record;
    }

    /** Removes a delivered record by id; false when it was already gone. */
    public boolean remove(final UUID player, final String id) {
        final List<PendingReward> list = pending.get(player);
        if (list == null) {
            return false;
        }
        final boolean removed = list.removeIf(record -> record.id().equals(id));
        if (list.isEmpty()) {
            pending.remove(player);
        }
        if (removed) {
            persist();
        }
        return removed;
    }

    /** Writes the file atomically. */
    public void persist() {
        try {
            final YamlConfiguration yaml = new YamlConfiguration();
            for (final Map.Entry<UUID, List<PendingReward>> entry : pending.entrySet()) {
                final List<Map<String, Object>> list = new ArrayList<>(entry.getValue().size());
                for (final PendingReward reward : entry.getValue()) {
                    list.add(reward.toMap());
                }
                yaml.set("pending." + entry.getKey(), list);
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException failure) {
            if (logger != null) {
                logger.severe("Could not save pending rewards: " + failure.getMessage());
            }
        }
    }

    private void warn(final String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
