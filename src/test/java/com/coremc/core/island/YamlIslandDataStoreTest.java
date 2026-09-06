package com.coremc.core.island;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlIslandDataStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadAllRoundTrip() throws IOException {
        final YamlIslandDataStore store = new YamlIslandDataStore(tempDir);
        final UUID owner = UUID.randomUUID();
        store.save(new Island(UUID.randomUUID(), owner, "world", 256, 64, 0, 99L));

        final Collection<Island> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        final Island island = loaded.iterator().next();
        assertEquals(owner, island.owner());
        assertEquals(256, island.centerX());
        assertEquals(99L, island.createdMillis());
    }

    @Test
    void deleteRemovesTheFile() throws IOException {
        final YamlIslandDataStore store = new YamlIslandDataStore(tempDir);
        final UUID owner = UUID.randomUUID();
        store.save(new Island(UUID.randomUUID(), owner, "world", 0, 64, 0, 0L));
        assertEquals(1, store.loadAll().size());

        store.delete(owner);
        assertTrue(store.loadAll().isEmpty());
        // deleting again is a no-op
        store.delete(owner);
    }

    @Test
    void corruptFileFailsLoadWithIOException() throws IOException {
        Files.writeString(tempDir.resolve(UUID.randomUUID() + ".yml"), "island: [nope\n");
        final YamlIslandDataStore store = new YamlIslandDataStore(tempDir);
        assertThrows(IOException.class, store::loadAll);
    }

    @Test
    void saveLeavesNoTempFiles() throws IOException {
        final YamlIslandDataStore store = new YamlIslandDataStore(tempDir);
        store.save(new Island(UUID.randomUUID(), UUID.randomUUID(), "world", 0, 64, 0, 0L));
        assertEquals(0L, Files.list(tempDir).filter(p -> p.toString().endsWith(".tmp")).count());
    }
}
