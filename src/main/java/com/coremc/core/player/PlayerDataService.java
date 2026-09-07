package com.coremc.core.player;

import com.coremc.core.scheduler.TaskService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
    /** Last-known username -> UUID (lowercased names). Rebuilt from joins; persisted. */
    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final ExecutorService io =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "CoreMC-PlayerData");
                thread.setDaemon(true);
                return thread;
            });

    private static final long LOAD_TIMEOUT_SECONDS = 4L;

    private volatile boolean shuttingDown = false;
    private org.bukkit.scheduler.BukkitTask autosaveTask;

    public PlayerDataService(final JavaPlugin plugin, final PlayerDataStore store, final TaskService tasks) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.store = store;
        this.tasks = tasks;
        io.execute(() -> {
            try {
                nameIndex.putAll(store.loadNameIndex());
                logger.fine("Loaded username index with " + nameIndex.size() + " entr(y/ies).");
            } catch (final IOException exception) {
                logger.log(Level.WARNING, "Failed to load username index — offline name lookups may fail.",
                        exception);
            }
        });
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
            // Run the load on the I/O executor (audited race): a pending quit-save
            // of the SAME player is therefore guaranteed to complete before this
            // load starts, so a quick reconnect can never resurrect stale data.
            final Optional<PlayerProfile> loaded =
                    io.submit(() -> store.load(uuid)).get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            final PlayerProfile profile =
                    loaded.orElseGet(() -> PlayerProfile.createNew(uuid, username, System.currentTimeMillis()));
            cache.put(uuid, profile);
        } catch (final java.util.concurrent.ExecutionException exception) {
            logger.log(Level.SEVERE, "Failed to load profile for " + uuid
                    + " — starting session with a fresh in-memory profile.", exception.getCause());
            cache.put(uuid, PlayerProfile.createNew(uuid, username, System.currentTimeMillis()));
        } catch (final java.util.concurrent.TimeoutException exception) {
            logger.log(Level.SEVERE, "Timed out loading profile for " + uuid
                    + " — starting session with a fresh in-memory profile.", exception);
            cache.put(uuid, PlayerProfile.createNew(uuid, username, System.currentTimeMillis()));
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
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
        rememberName(username, uuid);
        return Optional.of(profile);
    }

    /** Learns a username->UUID pair and persists the index asynchronously. */
    public void rememberName(final String username, final UUID uuid) {
        final String key = username.toLowerCase(java.util.Locale.ROOT);
        if (uuid.equals(nameIndex.put(key, uuid))) {
            return; // unchanged
        }
        io.execute(() -> {
            try {
                store.saveNameIndex(Map.copyOf(nameIndex));
            } catch (final IOException exception) {
                logger.log(Level.WARNING, "Failed to save username index", exception);
            }
        });
    }

    /** Resolves a player name to a UUID via the index, falling back to Bukkit's usercache. */
    public Optional<UUID> resolveUuid(final String username) {
        final UUID indexed = nameIndex.get(username.toLowerCase(java.util.Locale.ROOT));
        if (indexed != null) {
            return Optional.of(indexed);
        }
        final org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(username);
        if (offline.hasPlayedBefore()) {
            rememberName(username, offline.getUniqueId());
            return Optional.of(offline.getUniqueId());
        }
        return Optional.empty();
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

    /**
     * Cached profile if online, otherwise a one-off disk load. The
     * loaded-offline profile is NOT cached: mutate + {@link
     * #persistAfterEconomyChange} / save immediately and let it go.
     *
     * WARNING: may perform disk I/O on a cache miss — call off the main
     * thread unless the profile is known to be cached.
     */
    public Optional<PlayerProfile> cachedOrLoad(final UUID uuid) {
        final PlayerProfile cached = cache.get(uuid);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            return store.load(uuid);
        } catch (final IOException exception) {
            logger.log(Level.SEVERE, "Failed to load profile " + uuid, exception);
            return Optional.empty();
        }
    }

    /**
     * Persistence contract after an economy mutation:
     * Calls write-through immediately (use for rare, high-value changes:
     * currency, role selection, island association).
     */
    public void persistImportant(final PlayerProfile profile) {
        persistAfterEconomyChange(profile, true);
    }

    /** Marks a profile dirty for the autosave/quite flush (cheap changes: XP ticks). */
    public void markDirty(final UUID uuid) {
        dirty.add(uuid);
    }

    /**
     * Persistence contract after an economy mutation:
     * premium currencies (writeThrough=true) flush to disk immediately;
     * soft currency changes are marked dirty and ride the regular
     * autosave/quit/shutdown flush.
     */
    public void persistAfterEconomyChange(final PlayerProfile profile, final boolean writeThrough) {
        dirty.add(profile.uuid());
        if (writeThrough) {
            io.execute(() -> {
                if (saveQuietly(profile)) {
                    dirty.remove(profile.uuid());
                }
            });
        }
    }

    /**
     * Ghost-data tool for island deletion: clears the stored island
     * association iff it still points at {@code islandId}. Works for
     * online (cached) and offline (disk) profiles. May do disk I/O —
     * call from a worker thread.
     */
    public void clearIslandAssociationIfMatches(final UUID player, final UUID islandId) {
        final PlayerProfile profile = cachedOrLoad(player).orElse(null);
        if (profile == null || profile.islandId() == null || !profile.islandId().equals(islandId)) {
            return;
        }
        profile.islandId(null);
        dirty.add(player);
        io.execute(() -> {
            if (saveQuietly(profile)) {
                dirty.remove(player);
            }
        });
    }

    /** Runs work on the profile I/O executor (admin commands resolving offline players). */
    public void ioExecute(final Runnable work) {
        io.execute(work);
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
