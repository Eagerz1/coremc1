package com.coremc.core.player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlPlayerDataStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveThenLoadRoundTrip() throws IOException {
        final YamlPlayerDataStore store = new YamlPlayerDataStore(tempDir);
        final UUID uuid = UUID.randomUUID();

        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Steve", 1_000L);
        profile.recordLogin("Steve", 2_000L);
        store.save(profile);

        final Optional<PlayerProfile> loaded = store.load(uuid);
        assertTrue(loaded.isPresent());
        assertEquals("Steve", loaded.get().username());
        assertEquals(1L, loaded.get().totalLogins());
        assertEquals(1_000L, loaded.get().firstJoinMillis());
    }

    @Test
    void legacyProfileSavePreservesFutureFieldsAndProgress() throws IOException {
        final UUID uuid = UUID.randomUUID();
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(uuid + ".yml"), "profile:\n"
                + "  schema-version: 42\n"
                + "  username: Veteran\n"
                + "  first-join-millis: 123\n"
                + "  money: 9001\n"
                + "  role: miner\n"
                + "  future-feature:\n"
                + "    tier: 3\n"
                + "    rewards: [one, two]\n");

        final YamlPlayerDataStore store = new YamlPlayerDataStore(tempDir);
        final PlayerProfile loaded = store.load(uuid).orElseThrow();
        assertEquals(9_001L, loaded.money());
        loaded.recordLogin("Veteran", 456L);
        store.save(loaded);

        final PlayerProfile restored = store.load(uuid).orElseThrow();
        assertEquals(9_001L, restored.money());
        assertEquals(1L, restored.totalLogins());
        assertEquals(42, restored.toMap().get("schema-version"));
        assertEquals(Map.of("tier", 3, "rewards", java.util.List.of("one", "two")),
                restored.toMap().get("future-feature"));
    }

    @Test
    void loadMissingReturnsEmpty() throws IOException {
        final YamlPlayerDataStore store = new YamlPlayerDataStore(tempDir);
        assertTrue(store.load(UUID.randomUUID()).isEmpty());
    }

    @Test
    void corruptFileThrowsIOException() throws IOException {
        final YamlPlayerDataStore store = new YamlPlayerDataStore(tempDir);
        final UUID uuid = UUID.randomUUID();
        Files.writeString(tempDir.resolve(uuid + ".yml"), "profile: [unclosed\n  broken:: yaml");

        assertThrows(IOException.class, () -> store.load(uuid));
    }

    @Test
    void saveLeavesNoTempFileBehind() throws IOException {
        final YamlPlayerDataStore store = new YamlPlayerDataStore(tempDir);
        store.save(PlayerProfile.createNew(UUID.randomUUID(), "Alex", 1L));

        final long tempFiles = Files.list(tempDir).filter(p -> p.toString().endsWith(".tmp")).count();
        assertEquals(0L, tempFiles);
    }
}
