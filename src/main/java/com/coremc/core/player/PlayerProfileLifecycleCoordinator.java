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
public class PlayerProfileLifecycleCoordinator {

    private final Logger logger;
    private final PlayerDataStore store;
    private final TaskService tasks;

    private final ConcurrentHashMap<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();
    /** Attempt that most recently installed a pre-login profile for each UUID. */
    private final ConcurrentHashMap<UUID, PreLoginAttempt> cacheInstallOwner = new ConcurrentHashMap<>();
    /** Offline profile mutations awaiting a durable write or autosave retry. */
    private final ConcurrentHashMap<UUID, PlayerProfile> offlineDirty = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    /** Last-known username -> UUID (lowercased names). Rebuilt from joins; persisted. */
    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final ExecutorService io =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "CoreMC-PlayerData");
                thread.setDaemon(true);
                return thread;
            });

    private static final class PreLoginAttempt {
        private boolean cancelled;
        private boolean installed;
        private boolean pendingRemoved;
        private PlayerProfile profile;
        private PlayerProfile previous;
    }
    private static final long LOAD_TIMEOUT_SECONDS = 4L;

    private volatile boolean shuttingDown = false;
    private org.bukkit.scheduler.BukkitTask autosaveTask;

    public PlayerProfileLifecycleCoordinator(final JavaPlugin plugin, final PlayerDataStore store,
            final TaskService tasks) {
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
        this.autosaveTask = tasks.runTimerAsync(this::queueAutosave, periodTicks, periodTicks);
    }

    /**
     * Loads (or creates) the profile for a connecting player. Must be
     * called from an async context (pre-login is async on Paper).
     */
    public boolean handlePreLogin(final UUID uuid, final String username) {
        if (shuttingDown) {
            return false;
        }
        final PreLoginAttempt attempt = new PreLoginAttempt();
        final java.util.concurrent.Future<Boolean> load;
        try {
            load = io.submit(() -> {
                synchronized (attempt) {
                    if (attempt.cancelled || shuttingDown) {
                        return false;
                    }
                }
                final PlayerProfile alreadyCached = cache.get(uuid);
                final PlayerProfile alreadyPending = offlineDirty.get(uuid);
                final PlayerProfile selected;
                if (alreadyCached != null) {
                    selected = alreadyCached;
                } else if (alreadyPending != null) {
                    selected = alreadyPending;
                } else {
                    final Optional<PlayerProfile> loaded = store.load(uuid);
                    final PlayerProfile pendingAfterLoad = offlineDirty.get(uuid);
                    selected = pendingAfterLoad != null
                            ? pendingAfterLoad
                            : loaded.orElseGet(() -> PlayerProfile.createNew(
                                    uuid, username, System.currentTimeMillis()));
                }
                synchronized (attempt) {
                    if (attempt.cancelled || shuttingDown) {
                        return false;
                    }
                    attempt.previous = cache.put(uuid, selected);
                    cacheInstallOwner.put(uuid, attempt);
                    attempt.profile = selected;
                    attempt.installed = true;
                    attempt.pendingRemoved = offlineDirty.remove(uuid, selected);
                    if (attempt.pendingRemoved) {
                        dirty.add(uuid);
                    }
                    return true;
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException exception) {
            logger.log(Level.WARNING, "Player storage stopped before profile " + uuid
                    + " could be loaded; denying login.", exception);
            return false;
        }
        try {
            final boolean loadedSuccessfully = load.get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (loadedSuccessfully) {
                cacheInstallOwner.remove(uuid, attempt);
            }
            return loadedSuccessfully;
        } catch (final java.util.concurrent.ExecutionException exception) {
            rollbackPreLoginLoad(uuid, attempt);
            logger.log(Level.SEVERE, "Failed to load profile for " + uuid
                    + " — denying login to protect the saved profile.", exception.getCause());
        } catch (final java.util.concurrent.TimeoutException exception) {
            rollbackPreLoginLoad(uuid, attempt);
            load.cancel(true);
            logger.log(Level.SEVERE, "Timed out loading profile for " + uuid
                    + " — denying login to protect the saved profile.", exception);
        } catch (final InterruptedException exception) {
            rollbackPreLoginLoad(uuid, attempt);
            load.cancel(true);
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while loading profile for " + uuid
                    + " — denying login to protect the saved profile.", exception);
        }
        return false;
    }

    private void rollbackPreLoginLoad(final UUID uuid, final PreLoginAttempt attempt) {
        synchronized (attempt) {
            attempt.cancelled = true;
            final boolean ownsCacheInstall = cacheInstallOwner.remove(uuid, attempt);
            if (ownsCacheInstall && attempt.installed && cache.get(uuid) == attempt.profile) {
                if (attempt.previous == null) {
                    cache.remove(uuid, attempt.profile);
                } else {
                    cache.replace(uuid, attempt.profile, attempt.previous);
                }
            }
            if (attempt.installed && attempt.profile != null) {
                // A queued mutation may have run before the pre-login thread observed
                // its timeout. Preserve this exact object across cache rollback.
                offlineDirty.put(uuid, attempt.profile);
                dirty.add(uuid);
            }
        }
    }
    /** Records a successful login; returns the profile (may be absent in edge cases). */
    public Optional<PlayerProfile> handleJoin(final UUID uuid, final String username) {
        final PlayerProfile profile = cache.get(uuid);
        if (profile == null) {
            // Player joined without a pre-login (should never happen) — recover safely.
            return Optional.empty();
        }
        cacheInstallOwner.remove(uuid);
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
        dirty.remove(uuid); // changes after this point will re-add the UUID.
        io.execute(() -> {
            if (saveQuietly(profile)) {
                offlineDirty.remove(uuid, profile);
                // Only evict after the save so a quick reconnect re-uses the cache entry.
                cache.remove(uuid, profile);
            } else {
                dirty.add(uuid);
            }
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
        final PlayerProfile pending = offlineDirty.get(uuid);
        if (pending != null) {
            return Optional.of(pending);
        }
        try {
            final Optional<PlayerProfile> loaded = store.load(uuid);
            final PlayerProfile cachedAfterLoad = cache.get(uuid);
            if (cachedAfterLoad != null) {
                return Optional.of(cachedAfterLoad);
            }
            final PlayerProfile pendingAfterLoad = offlineDirty.get(uuid);
            return pendingAfterLoad == null ? loaded : Optional.of(pendingAfterLoad);
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
     * Premium currencies flush immediately; cached soft currency rides autosave,
     * while uncached offline profiles are written immediately and retried on failure.
     */
    public void persistAfterEconomyChange(final PlayerProfile profile, final boolean writeThrough) {
        final UUID uuid = profile.uuid();
        final boolean cachedProfile = cache.get(uuid) == profile;
        dirty.add(uuid);
        if (writeThrough || !cachedProfile) {
            dirty.remove(uuid); // a later mutation will re-add the UUID.
            if (!cachedProfile) {
                offlineDirty.put(uuid, profile);
            }
            try {
                io.execute(() -> {
                    if (saveQuietly(profile)) {
                        if (!cachedProfile) {
                            offlineDirty.remove(uuid, profile);
                        }
                    } else {
                        dirty.add(uuid);
                        if (!cachedProfile) {
                            offlineDirty.put(uuid, profile);
                        }
                    }
                });
            } catch (final java.util.concurrent.RejectedExecutionException exception) {
                dirty.add(uuid);
                if (!cachedProfile) {
                    offlineDirty.put(uuid, profile);
                }
                logger.log(Level.SEVERE, "Could not queue profile save for " + uuid, exception);
            }
        }
    }
    /**
     * Ghost-data tool for island deletion: clears the stored island
     * association iff it still points at {@code islandId}. Works for
     * online (cached) and offline (disk) profiles. May do disk I/O —
     * call from a worker thread.
     */
    public void clearIslandAssociationIfMatches(final UUID player, final UUID islandId) {
        if (shuttingDown) {
            return;
        }
        try {
            io.execute(() -> clearIslandAssociationOnIo(player, islandId));
        } catch (final java.util.concurrent.RejectedExecutionException exception) {
            logger.log(Level.WARNING, "Could not queue island association cleanup for " + player, exception);
        }
    }

    /** Runs with all offline profile loads and writes serialized on this service's executor. */
    private void clearIslandAssociationOnIo(final UUID player, final UUID islandId) {
        final PlayerProfile profile = cachedOrLoad(player).orElse(null);
        if (profile == null || profile.islandId() == null || !profile.islandId().equals(islandId)) {
            return;
        }
        synchronized (profile) {
            if (profile.islandId() == null || !profile.islandId().equals(islandId)) {
                return;
            }
            profile.islandId(null);
        }
        final boolean cachedProfile = cache.get(player) == profile;
        if (!cachedProfile) {
            offlineDirty.put(player, profile);
        }
        dirty.remove(player); // a subsequent profile change will add it again.
        try {
            io.execute(() -> {
                if (saveQuietly(profile)) {
                    if (!cachedProfile) {
                        offlineDirty.remove(player, profile);
                    }
                } else {
                    dirty.add(player);
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException exception) {
            dirty.add(player);
            logger.log(Level.SEVERE, "Could not queue island association save for " + player, exception);
        }
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
        queueAutosave();
    }

    /** Submits periodic work to the single writer so profile files never race. */
    private void queueAutosave() {
        if (shuttingDown) {
            return;
        }
        try {
            io.execute(() -> {
                if (!shuttingDown) {
                    autosaveDirty();
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException exception) {
            if (!shuttingDown) {
                logger.log(Level.WARNING, "Could not queue player profile autosave", exception);
            }
        }
    }

    /** Flushes every cached profile to disk after queued writes. */
    public void saveAllSync() {
        final Map<UUID, PlayerProfile> profilesById = new java.util.LinkedHashMap<>(offlineDirty);
        profilesById.putAll(cache);
        final Collection<PlayerProfile> profiles = new ArrayList<>(profilesById.values());
        try {
            // Queue the final snapshot behind any in-flight writes so an older
            // asynchronous save cannot overwrite this shutdown save afterward.
            io.submit(() -> {
                        store.saveAll(profiles);
                        return null;
                    })
                    .get(10, TimeUnit.SECONDS);
            for (final PlayerProfile profile : profiles) {
                offlineDirty.remove(profile.uuid(), profile);
            }
            dirty.clear();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Interrupted while saving all player profiles", exception);
        } catch (final java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException
                | java.util.concurrent.RejectedExecutionException exception) {
            logger.log(Level.SEVERE, "Failed to save all player profiles", exception);
        }
    }
    /**
     * Stops accepting work, saves everything, and shuts the I/O executor down.
     * Must be called from onDisable, on the main thread.
     */
    public void shutdown() {
        shuttingDown = true;
        saveAllSync();
        try {
            // This retry is queued behind writes that may have been submitted by
            // an admin operation already running when shutdown began.
            io.submit(() -> {
                        final Map<UUID, PlayerProfile> remaining = new java.util.LinkedHashMap<>(offlineDirty);
                        remaining.putAll(cache);
                        for (final Map.Entry<UUID, PlayerProfile> entry : remaining.entrySet()) {
                            if (saveQuietly(entry.getValue())) {
                                offlineDirty.remove(entry.getKey(), entry.getValue());
                                dirty.remove(entry.getKey());
                            } else {
                                dirty.add(entry.getKey());
                            }
                        }
                    })
                    .get(10, TimeUnit.SECONDS);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Interrupted while retrying player profile saves during shutdown.", exception);
        } catch (final java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException
                | java.util.concurrent.RejectedExecutionException exception) {
            logger.log(Level.SEVERE, "Could not complete the bounded player save retry during shutdown.", exception);
        }
        if (!dirty.isEmpty() || !offlineDirty.isEmpty()) {
            logger.severe("Player data remains dirty at shutdown for "
                    + Math.max(dirty.size(), offlineDirty.size()) + " profile(s).");
        }
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
        offlineDirty.clear();
    }
    private void autosaveDirty() {
        if (dirty.isEmpty()) {
            return;
        }
        final List<UUID> snapshot = new ArrayList<>(dirty);
        int saved = 0;
        for (final UUID uuid : snapshot) {
            if (!dirty.remove(uuid)) {
                continue;
            }
            PlayerProfile profile = cache.get(uuid);
            final boolean cachedProfile = profile != null;
            if (profile == null) {
                profile = offlineDirty.get(uuid);
            }
            if (profile == null) {
                continue;
            }
            if (saveQuietly(profile)) {
                offlineDirty.remove(uuid, profile);
                saved++;
            } else {
                dirty.add(uuid);
            }
        }
        if (saved > 0) {
            logger.fine("Autosaved " + saved + " player profile(s).");
        }
    }

    /** Load or reuse a profile on the serialized profile I/O queue for startup recovery. */
    public java.util.concurrent.CompletableFuture<Optional<PlayerProfile>> loadForPersistence(final UUID uuid) {
        final java.util.concurrent.CompletableFuture<Optional<PlayerProfile>> result = new java.util.concurrent.CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    result.complete(cachedOrLoad(uuid));
                } catch (final RuntimeException exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    /** Queue and await one durable profile write; callers must wait off the server thread. */
    public java.util.concurrent.CompletableFuture<Boolean> persistAndWait(final PlayerProfile profile) {
        final java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    store.save(profile);
                    result.complete(true);
                } catch (final IOException | RuntimeException exception) {
                    logger.log(Level.SEVERE, "Failed to persist transaction profile " + profile.uuid(), exception);
                    result.complete(false);
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException exception) {
            result.complete(false);
        }
        return result;
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
