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
        final UUID member = UUID.randomUUID();
        final Island island = new Island(UUID.randomUUID(), owner, "world", 256, 64, 0, 50, 99L);
        island.addMember(member);
        island.setUpgradeTier("member-slots", 1);
        store.save(island);

        final Collection<Island> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        final Island restored = loaded.iterator().next();
        assertEquals(owner, restored.owner());
        assertEquals(member, restored.members().iterator().next());
        assertEquals(256, restored.centerX());
        assertEquals(50, restored.borderSize());
        assertEquals(1, restored.upgrades().get("member-slots"));
        assertEquals(99L, restored.createdMillis());
    }

    @Test
    void legacyV1FileLoadsWithDefaults() throws IOException {
        Files.createDirectories(tempDir);
        final UUID owner = UUID.randomUUID();
        final UUID islandId = UUID.randomUUID();
        Files.writeString(
                tempDir.resolve(owner + ".yml"),
                "island:\n"
                        + "  island-id: " + islandId + "\n"
                        + "  owner: " + owner + "\n"
                        + "  world: world\n"
                        + "  center-x: 0\n"
                        + "  center-y: 64\n"
                        + "  center-z: 0\n"
                        + "  created-millis: 42\n");
        final YamlIslandDataStore store = new YamlIslandDataStore(tempDir);
        final Collection<Island> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        final Island island = loaded.iterator().next();
        assertEquals(Island.DEFAULT_BORDER_SIZE, island.borderSize());
        assertEquals(1, island.level());
        assertTrue(island.members().isEmpty());
    }

    @Test
    void deleteRemovesTheFile() throws IOException {
        final YamlIslandDataStore store = new YamlIslandDataStore(tempDir);
        final UUID owner = UUID.randomUUID();
        store.save(new Island(UUID.randomUUID(), owner, "world", 0, 64, 0, 50, 0L));
        assertEquals(1, store.loadAll().size());

        store.delete(owner);
        assertTrue(store.loadAll().isEmpty());
        store.delete(owner); // deleting again is a no-op
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
        store.save(new Island(UUID.randomUUID(), UUID.randomUUID(), "world", 0, 64, 0, 50, 0L));
        assertEquals(0L, Files.list(tempDir).filter(p -> p.toString().endsWith(".tmp")).count());
    }
}
