package com.coremc.core.spawner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spawner persistence round-trips: luck levels and placed spawner
 * registrations survive a save/load cycle; corrupt files start fresh
 * instead of crashing.
 */
class YamlSpawnerDataStoreTest {

    private static final Logger LOGGER = Logger.getLogger(YamlSpawnerDataStoreTest.class.getName());

    @TempDir
    Path dir;

    @Test
    void luckAndSpawnersRoundTrip() throws IOException {
        final Path file = dir.resolve("spawners-data.yml");
        final YamlSpawnerDataStore store = new YamlSpawnerDataStore(file, LOGGER);

        final UUID island = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final Map<UUID, Integer> luck = new LinkedHashMap<>();
        luck.put(island, 3);
        final List<SpawnerEntry> spawners = List.of(
                new SpawnerEntry("world_coremc_islands", 10, 65, -4, "pig",
                        SpawnerVariant.ADVANCED, island),
                new SpawnerEntry("world_coremc_islands", -100, 70, 200, "blaze",
                        SpawnerVariant.MYTHIC, island));

        store.save(luck, spawners);
        assertTrue(Files.isRegularFile(file), "file written");

        final YamlSpawnerDataStore reloaded = new YamlSpawnerDataStore(file, LOGGER);
        assertEquals(Map.of(island, 3), reloaded.loadLuck());
        final List<SpawnerEntry> loaded = reloaded.loadSpawners();
        assertEquals(spawners, loaded);
        assertEquals("world_coremc_islands:10:65:-4", loaded.get(0).key());
    }

    @Test
    void stackedAmountRoundTrips() throws IOException {
        final Path file = dir.resolve("spawners-data.yml");
        final YamlSpawnerDataStore store = new YamlSpawnerDataStore(file, LOGGER);
        final UUID island = UUID.fromString("00000000-0000-0000-0000-000000000002");
        final List<SpawnerEntry> spawners = List.of(
                new SpawnerEntry("world_coremc_islands", 4, 65, 4, "pig",
                        SpawnerVariant.NORMAL, island, 32));
        store.save(Map.of(), spawners);
        final List<SpawnerEntry> loaded = new YamlSpawnerDataStore(file, LOGGER).loadSpawners();
        assertEquals(32, loaded.get(0).amount());
        assertEquals(spawners, loaded);
    }

    @Test
    void stackedMythicSpawnerRoundTrips() throws IOException {
        final Path file = dir.resolve("spawners-data.yml");
        final YamlSpawnerDataStore store = new YamlSpawnerDataStore(file, LOGGER);
        final UUID island = UUID.fromString("00000000-0000-0000-0000-000000000003");
        final Map<UUID, Integer> luck = Map.of(island, 4);
        final List<SpawnerEntry> spawners = List.of(
                new SpawnerEntry("world_coremc_islands", -4, 70, 7, "pig",
                        SpawnerVariant.MYTHIC, island, 2));
        store.save(luck, spawners);
        final YamlSpawnerDataStore reloaded = new YamlSpawnerDataStore(file, LOGGER);
        assertEquals(luck, reloaded.loadLuck());
        final List<SpawnerEntry> loaded = reloaded.loadSpawners();
        assertEquals(1, loaded.size());
        assertEquals(SpawnerVariant.MYTHIC, loaded.get(0).variant());
        assertEquals(2, loaded.get(0).amount());
        assertEquals(spawners, loaded);
    }

    @Test
    void legacyEntriesWithoutAmountLoadAsSingle() throws IOException {
        final Path file = dir.resolve("spawners-data.yml");
        Files.writeString(file, """
                spawners:
                - world: world_coremc_islands
                  x: 10
                  y: 65
                  z: -4
                  mob: pig
                  variant: NORMAL
                  island: 00000000-0000-0000-0000-000000000001
                """, StandardCharsets.UTF_8);
        final List<SpawnerEntry> loaded = new YamlSpawnerDataStore(file, LOGGER).loadSpawners();
        assertEquals(1, loaded.size());
        assertEquals(1, loaded.get(0).amount());
    }

    @Test
    void missingFileLoadsEmpty() throws IOException {
        final YamlSpawnerDataStore store = new YamlSpawnerDataStore(dir.resolve("nope.yml"), LOGGER);
        assertTrue(store.loadLuck().isEmpty());
        assertTrue(store.loadSpawners().isEmpty());
    }

    @Test
    void corruptFileStartsFresh() throws IOException {
        final Path file = dir.resolve("spawners-data.yml");
        Files.writeString(file, "luck: [this: is: not: valid: {", StandardCharsets.UTF_8);
        final YamlSpawnerDataStore store = new YamlSpawnerDataStore(file, LOGGER);
        assertTrue(store.loadLuck().isEmpty());
        assertTrue(store.loadSpawners().isEmpty());
    }

    @Test
    void badLuckEntriesAreSkipped() throws IOException {
        final Path file = dir.resolve("spawners-data.yml");
        Files.writeString(file, """
                luck:
                  not-a-uuid: 2
                  00000000-0000-0000-0000-000000000009: 4
                """, StandardCharsets.UTF_8);
        final YamlSpawnerDataStore store = new YamlSpawnerDataStore(file, LOGGER);
        assertEquals(Map.of(UUID.fromString("00000000-0000-0000-0000-000000000009"), 4),
                store.loadLuck());
    }

    @Test
    void saveIsAtomicNoTempLeftBehind() throws IOException {
        final Path file = dir.resolve("spawners-data.yml");
        final YamlSpawnerDataStore store = new YamlSpawnerDataStore(file, LOGGER);
        store.save(new LinkedHashMap<>(), List.of());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "only the store file, no .tmp leftover");
        }
    }
}
