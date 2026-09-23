package com.coremc.core.island;

import com.coremc.core.spawner.SpawnerService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Timed island buffs: which buff runs on which island until when.
 * Buffs survive restarts with their remaining time; expiry is enforced
 * lazily on read and by a periodic sweep that also reverts lasting
 * world effects (currently the spawner boost).
 */
public final class IslandBuffService {

    /** How often expired buffs are swept (and spawner boosts reverted). */
    private static final long SWEEP_TICKS = 20L * 20L;

    private final JavaPlugin plugin;
    private final YamlBuffStore store;
    private final SpawnerService spawners;
    private final IslandUpgradeConfig config;
    private final Logger logger;

    /** island id -> buff id -> expiry epoch millis. */
    private final Map<UUID, Map<String, Long>> active = new LinkedHashMap<>();

    public IslandBuffService(final JavaPlugin plugin, final YamlBuffStore store,
                             final SpawnerService spawners, final IslandUpgradeConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.spawners = spawners;
        this.config = config;
        this.logger = plugin.getLogger();
    }

    /** Loads persisted buffs, re-applies their lasting effects and starts the expiry sweep. */
    public void load() {
        try {
            active.putAll(store.load());
        } catch (final IOException exception) {
            logger.severe("Could not load buffs.yml: " + exception.getMessage());
        }
        reapplyEffects();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, SWEEP_TICKS, SWEEP_TICKS);
    }

    /** Re-applies lasting world effects (currently the spawner boost) after a restart. */
    public void reapplyEffects() {
        if (spawners == null || config == null) {
            return;
        }
        final IslandUpgradeConfig.BuffDef boost = config.buff("spawner-boost");
        if (boost == null) {
            return;
        }
        for (final Map.Entry<UUID, Map<String, Long>> island : active.entrySet()) {
            final Long expiry = island.getValue().get("spawner-boost");
            if (expiry != null && expiry > System.currentTimeMillis()) {
                spawners.setBoost(island.getKey(), boost.multiplier());
            }
        }
    }

    /** Whether the buff is currently running on the island. */
    public boolean isActive(final Island island, final String buffId) {
        if (island == null) {
            return false;
        }
        final Map<String, Long> buffs = active.get(island.id());
        if (buffs == null) {
            return false;
        }
        final Long expiry = buffs.get(buffId);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    /** Remaining buff milliseconds, rounded up to whole seconds (0 when inactive). */
    public long remainingMillis(final Island island, final String buffId) {
        if (island == null) {
            return 0L;
        }
        final Map<String, Long> buffs = active.get(island.id());
        if (buffs == null) {
            return 0L;
        }
        final Long expiry = buffs.get(buffId);
        if (expiry == null) {
            return 0L;
        }
        return Math.max(0L, expiry - System.currentTimeMillis());
    }

    /** Remaining whole minutes (rounded down, 0 when under a minute is left). */
    public long remainingMinutes(final Island island, final String buffId) {
        return remainingMillis(island, buffId) / 60_000L;
    }

    /**
     * Starts (or restarts) a buff on the island, applying its lasting
     * effects immediately. Returns the expiry epoch.
     */
    public long activate(final Island island, final IslandUpgradeConfig.BuffDef buff) {
        final long expiry = System.currentTimeMillis() + buff.durationMinutes() * 60_000L;
        active.computeIfAbsent(island.id(), id -> new LinkedHashMap<>()).put(buff.id(), expiry);
        persist();
        if (spawners != null && "spawner-boost".equals(buff.id())) {
            spawners.setBoost(island.id(), buff.multiplier());
        }
        return expiry;
    }

    /** Removes expired buffs and reverts their lasting effects. */
    private void sweep() {
        final long now = System.currentTimeMillis();
        boolean changed = false;
        for (final Map.Entry<UUID, Map<String, Long>> island : active.entrySet()) {
            final List<String> expired = island.getValue().entrySet().stream()
                    .filter(buff -> buff.getValue() <= now)
                    .map(Map.Entry::getKey)
                    .toList();
            for (final String buffId : expired) {
                island.getValue().remove(buffId);
                changed = true;
                if (spawners != null && "spawner-boost".equals(buffId)) {
                    spawners.setBoost(island.getKey(), 1.0);
                }
            }
        }
        active.values().removeIf(Map::isEmpty);
        if (changed) {
            persist();
        }
    }

    private void persist() {
        try {
            store.save(active);
        } catch (final IOException exception) {
            logger.severe("Could not save buffs.yml: " + exception.getMessage());
        }
    }

    /** Removes all buffs for a deleted island. */
    public void onIslandDeleted(final Island island) {
        if (active.remove(island.id()) != null) {
            persist();
        }
    }
}
