package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** YAML persistence: atomic per-owner files, faithful round-trips. */
class YamlIslandDataStoreTest {

    @TempDir
    Path directory;

    private Island sampleIsland() {
        final Island island = new Island(
                UUID.randomUUID(),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "OwnerName",
                "coremc_islands",
                7,
                -400,
                64,
                600,
                100,
                "default",
                1697000000000L,
                -399.5,
                68.0,
                602.5,
                180f,
                0f);
        island.addMember(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        island.addMember(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        return island;
    }

    @Test
    void savesAndLoadsBackEveryField() throws IOException {
        final YamlIslandDataStore store = new YamlIslandDataStore(directory, Logger.getLogger("test"));
        final Island island = sampleIsland();
        store.save(island);

        assertTrue(Files.exists(directory.resolve(island.owner() + ".yml")), "one file per owner");
        final Collection<Island> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        final Island restored = loaded.iterator().next();

        assertEquals(island.id(), restored.id());
        assertEquals(island.owner(), restored.owner());
        assertEquals(island.ownerName(), restored.ownerName());
        assertEquals(island.worldName(), restored.worldName());
        assertEquals(island.slot(), restored.slot());
        assertEquals(island.centerX(), restored.centerX());
        assertEquals(island.baseY(), restored.baseY());
        assertEquals(island.centerZ(), restored.centerZ());
        assertEquals(island.borderSize(), restored.borderSize());
        assertEquals(island.schematic(), restored.schematic());
        assertEquals(island.createdAt(), restored.createdAt());
        assertEquals(island.members(), restored.members());
    }

    @Test
    void overwritingSavesInPlace() throws IOException {
        final YamlIslandDataStore store = new YamlIslandDataStore(directory, Logger.getLogger("test"));
        final Island island = sampleIsland();
        store.save(island);
        island.removeMember(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        store.save(island);

        assertEquals(1, store.loadAll().size());
        assertEquals(1, store.loadAll().iterator().next().members().size());
    }

    @Test
    void deleteRemovesTheFile() throws IOException {
        final YamlIslandDataStore store = new YamlIslandDataStore(directory, Logger.getLogger("test"));
        final Island island = sampleIsland();
        store.save(island);
        store.delete(island.owner());
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void corruptFilesAreSkippedNotFatal() throws IOException {
        final YamlIslandDataStore store = new YamlIslandDataStore(directory, Logger.getLogger("test"));
        final Island island = sampleIsland();
        store.save(island);
        Files.writeString(directory.resolve("deadbeef-0000-0000-0000-000000000000.yml"),
                "island: {not: enough}");

        final Collection<Island> loaded = store.loadAll();
        assertEquals(1, loaded.size(), "the valid island still loads");
    }

    @Test
    void emptyDirectoryLoadsEmpty() throws IOException {
        final YamlIslandDataStore store = new YamlIslandDataStore(directory, Logger.getLogger("test"));
        assertTrue(store.loadAll().isEmpty());
    }
}
