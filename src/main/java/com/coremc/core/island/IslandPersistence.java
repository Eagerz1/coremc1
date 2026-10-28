package com.coremc.core.island;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Owns island storage access and its ordered background I/O lane.
 *
 * The service layer captures model snapshots before enqueueing writes; this
 * component keeps the existing YAML backend replaceable without changing the
 * on-disk format or the public island command facade.
 */
public final class IslandPersistence {

    private final IslandDataStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "CoreMC-Islands");
        thread.setDaemon(true);
        return thread;
    });

    public IslandPersistence(final IslandDataStore store) {
        this.store = store;
    }

    public Collection<Island> loadAll() throws IOException {
        return store.loadAll();
    }

    public void save(final Island island) throws IOException {
        store.save(island);
    }

    public void saveSnapshot(final Map<String, Object> snapshot) throws IOException {
        store.saveSnapshot(snapshot);
    }

    public void delete(final UUID owner) throws IOException {
        store.delete(owner);
    }

    public void execute(final Runnable task) {
        io.execute(task);
    }

    public Future<?> submit(final Runnable task) {
        return io.submit(task);
    }

    public void shutdown() {
        io.shutdown();
    }

    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
        return io.awaitTermination(timeout, unit);
    }
}
