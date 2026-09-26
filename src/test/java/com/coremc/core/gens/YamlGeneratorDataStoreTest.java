package com.coremc.core.gens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generator persistence round-trips: placed generators (including
 * stacks, owners and islands) survive a save/load cycle, and corrupt
 * or partial files start fresh instead of crashing — placed
 * generators must never vanish because of one bad line.
 */
class YamlGeneratorDataStoreTest {

    private static final Logger LOGGER = Logger.getLogger(YamlGeneratorDataStoreTest.class.getName());

    @TempDir
    Path dir;

    @Test
    void generatorsRoundTrip() throws IOException {
        final Path file = dir.resolve("generators-data.yml");
        final YamlGeneratorDataStore store = new YamlGeneratorDataStore(file, LOGGER);

        final UUID island = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID owner = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        final List<GeneratorEntry> entries = List.of(
                new GeneratorEntry("coremc_islands", 10, 65, -4, "iron", island, owner, 8),
                new GeneratorEntry("coremc_islands", -100, 70, 200, "netherite", island, owner));

        store.save(entries);
        assertTrue(Files.isRegularFile(file), "file written");

        final List<GeneratorEntry> loaded = new YamlGeneratorDataStore(file, LOGGER).load();
        assertEquals(entries, loaded);
        assertEquals("coremc_islands:10:65:-4", loaded.get(0).key());
        assertEquals(8, loaded.get(0).amount());
        assertEquals(1, loaded.get(1).amount());
    }

    @Test
    void anEmptyStoreLoadsNothing() throws IOException {
        final YamlGeneratorDataStore store =
                new YamlGeneratorDataStore(dir.resolve("missing.yml"), LOGGER);
        assertTrue(store.load().isEmpty());
    }

    @Test
    void badEntriesAreSkippedAndTheRestSurvive() throws IOException {
        final Path file = dir.resolve("generators-data.yml");
        Files.writeString(file, """
                generators:
                - world: coremc_islands
                  x: 1
                  y: 2
                  z: 3
                  gen: iron
                  island: not-a-uuid
                  amount: 2
                - world: coremc_islands
                  x: 4
                  y: 5
                  z: 6
                  gen: gold
                  island: 00000000-0000-0000-0000-000000000009
                  amount: 3
                """, StandardCharsets.UTF_8);

        final List<GeneratorEntry> loaded = new YamlGeneratorDataStore(file, LOGGER).load();
        assertEquals(1, loaded.size());
        assertEquals("gold", loaded.get(0).genId());
        assertEquals(3, loaded.get(0).amount());
        assertNull(loaded.get(0).owner(), "a missing owner stays null (island owner is paid)");
    }

    @Test
    void aCorruptFileStartsFreshInsteadOfThrowing() throws IOException {
        final Path file = dir.resolve("generators-data.yml");
        Files.writeString(file, "generators: [this: is: not: yaml", StandardCharsets.UTF_8);
        assertTrue(new YamlGeneratorDataStore(file, LOGGER).load().isEmpty());
    }

    @Test
    void savingReplacesTheWholeFileAtomically() throws IOException {
        final Path file = dir.resolve("generators-data.yml");
        final YamlGeneratorDataStore store = new YamlGeneratorDataStore(file, LOGGER);
        final UUID island = UUID.randomUUID();
        store.save(List.of(new GeneratorEntry("w", 1, 2, 3, "iron", island, null, 4)));
        store.save(List.of());
        assertTrue(store.load().isEmpty());
        assertTrue(Files.list(dir).noneMatch(path -> path.toString().endsWith(".tmp")),
                "the temp file never survives a save");
    }
}
