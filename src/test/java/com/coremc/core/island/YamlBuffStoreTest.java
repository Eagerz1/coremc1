package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Buff persistence round-trips: active buffs survive a save/load
 * cycle with their expiry, and a corrupt file starts fresh instead of
 * crashing.
 */
class YamlBuffStoreTest {

    private static final Logger LOGGER = Logger.getLogger(YamlBuffStoreTest.class.getName());

    @TempDir
    Path dir;

    @Test
    void buffsRoundTripWithTheirExpiry() throws IOException {
        final Path file = dir.resolve("buffs.yml");
        final YamlBuffStore store = new YamlBuffStore(file, LOGGER);

        final UUID islandA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID islandB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        final Map<UUID, Map<String, Long>> active = new LinkedHashMap<>();
        active.put(islandA, new LinkedHashMap<>(Map.of(
                "crop-growth", 1_700_000_000_000L,
                "spawner-boost", 1_700_000_100_000L)));
        active.put(islandB, new LinkedHashMap<>(Map.of("xp-boost", 1_700_000_200_000L)));
        store.save(active);

        assertTrue(Files.isRegularFile(file), "buffs.yml is written eagerly");
        final Map<UUID, Map<String, Long>> loaded = store.load();
        assertEquals(active, loaded, "every island and buff survives the round-trip");
        assertEquals(1_700_000_000_000L, loaded.get(islandA).get("crop-growth"));
    }

    @Test
    void missingFileLoadsEmpty() throws IOException {
        final YamlBuffStore store = new YamlBuffStore(dir.resolve("buffs.yml"), LOGGER);
        assertTrue(store.load().isEmpty());
    }

    @Test
    void corruptFileStartsFresh() throws IOException {
        final Path file = dir.resolve("buffs.yml");
        Files.writeString(file, "luck: [this: is: not: valid: {",
                StandardCharsets.UTF_8);
        final YamlBuffStore store = new YamlBuffStore(file, LOGGER);
        assertTrue(store.load().isEmpty(), "corrupt buff data never crashes the plugin");
    }

    @Test
    void badIslandIdsAreSkipped() throws IOException {
        final Path file = dir.resolve("buffs.yml");
        Files.writeString(file, String.join("\n",
                "islands:",
                "  not-a-uuid:",
                "    crop-growth: 123",
                "  00000000-0000-0000-0000-000000000009:",
                "    xp-boost: 456"),
                StandardCharsets.UTF_8);
        final YamlBuffStore store = new YamlBuffStore(file, LOGGER);
        final Map<UUID, Map<String, Long>> loaded = store.load();
        assertEquals(1, loaded.size(), "only the well-formed island loads");
        assertEquals(456L, loaded.get(UUID.fromString("00000000-0000-0000-0000-000000000009"))
                .get("xp-boost"));
    }
}
