package com.coremc.core.player;

import com.coremc.core.scheduler.TaskService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns the lifecycle of {@link PlayerProfile}s: load on login (async),
 * cache while online, save on quit / autosave / shutdown.
 *
 * Threading rules:
 *  - Profiles are cached in a concurrent map keyed by UUID.
 *  - Disk I/O runs on a single daemon executor so saves are serialised
 *    and never hit the main thread.
 *  - Profiles whose owners are offline are evicted from the cache after
 *    their quit-save completes, so nothing player-scoped leaks.
 */
public final class PlayerDataService {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PlayerDataStore store;
    private final TaskService tasks;

    private final ConcurrentHashMap<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final ExecutorService io =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "CoreMC-PlayerData");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean shuttingDown = false;
    private org.bukkit.scheduler.BukkitTask autosaveTask;

    public PlayerDataService(final JavaPlugin plugin, final PlayerDataStore store, final TaskService tasks) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.store = store;
        this.tasks = tasks;
    }

    /**
     * Starts the recurring autosave task. Call again after config reload
     * to apply a new interval — the previous task is cancelled first.
     */
    public void startAutosave(final long autosaveSeconds) {
        if (autosaveTask != null) {
            tasks.cancel(autosaveTask);
        }
        final long periodTicks = autosaveSeconds * 20L;
        this.autosaveTask = tasks.runTimerAsync(this::autosaveDirty, periodTicks, periodTicks);
    }

    /**
     * Loads (or creates) the profile for a connecting player. Must be
     * called from an async context (pre-login is async on Paper).
     */
    public void handlePreLogin(final UUID uuid, final String username) {
        if (shuttingDown) {
            return;
        }
        try {
            final Optional<PlayerProfile> loaded = store.load(uuid);
            final PlayerProfile profile =
                    loaded.orElseGet(() -> PlayerProfile.createNew(uuid, username, System.currentTimeMillis()));
            cache.put(uuid, profile);
        } catch (final IOException exception) {
            logger.log(Level.SEVERE, "Failed to load profile for " + uuid
                    + " — starting session with a fresh in-memory profile.", exception);
            cache.put(uuid, PlayerProfile.createNew(uuid, username, System.currentTimeMillis()));
        }
    }

    /** Records a successful login; returns the profile (may be absent in edge cases). */
    public Optional<PlayerProfile> handleJoin(final UUID uuid, final String username) {
        final PlayerProfile profile = cache.get(uuid);
        if (profile == null) {
            // Player joined without a pre-login (should never happen) — recover safely.
            return Optional.empty();
        }
        profile.recordLogin(username, System.currentTimeMillis());
        dirty.add(uuid);
        return Optional.of(profile);
    }

    /** Saves and evicts the profile of a disconnecting player. */
    public void handleQuit(final UUID uuid) {
        final PlayerProfile profile = cache.get(uuid);
        if (profile == null) {
            return;
        }
        profile.recordQuit(System.currentTimeMillis());
        dirty.add(uuid);

        if (shuttingDown) {
            return; // saveAllSync during shutdown will pick this up
        }
        io.execute(() -> {
            saveQuietly(profile);
            dirty.remove(uuid);
            // Only evict after the save so a quick reconnect re-uses the cache entry.
            cache.remove(uuid, profile);
        });
    }

    /** Online player's cached profile, if present. */
    public Optional<PlayerProfile> profileOf(final UUID uuid) {
        return Optional.ofNullable(cache.get(uuid));
    }

    /** Number of profiles currently cached (roughly = online players). */
    public int cachedProfileCount() {
        return cache.size();
    }

    /** Flushes every dirty profile to disk, asynchronously. */
    public void saveAllAsync() {
        io.execute(this::autosaveDirty);
    }

    /** Flushes every cached profile to disk ON THE CALLING THREAD. */
    public void saveAllSync() {
        final Collection<PlayerProfile> profiles = new ArrayList<>(cache.values());
        try {
            store.saveAll(profiles);
        } catch (final IOException exception) {
            logger.log(Level.SEVERE, "Failed to save all player profiles", exception);
        }
        dirty.clear();
    }

    /**
     * Stops accepting work, saves everything, and shuts the I/O executor down.
     * Must be called from onDisable, on the main thread.
     */
    public void shutdown() {
        shuttingDown = true;
        saveAllSync();
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Player data executor did not terminate in 10s.");
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while stopping player data service", exception);
        }
        cache.clear();
        dirty.clear();
    }

    private void autosaveDirty() {
        if (dirty.isEmpty()) {
            return;
        }
        final List<UUID> snapshot = new ArrayList<>(dirty);
        int saved = 0;
        for (final UUID uuid : snapshot) {
            final PlayerProfile profile = cache.get(uuid);
            if (profile == null) {
                dirty.remove(uuid);
                continue;
            }
            if (saveQuietly(profile)) {
                dirty.remove(uuid);
                saved++;
            }
        }
        if (saved > 0) {
            logger.fine("Autosaved " + saved + " player profile(s).");
        }
    }

    private boolean saveQuietly(final PlayerProfile profile) {
        try {
            store.save(profile);
            return true;
        } catch (final IOException exception) {
            logger.log(Level.SEVERE, "Failed to save profile " + profile.uuid(), exception);
            return false;
        }
    }
}
