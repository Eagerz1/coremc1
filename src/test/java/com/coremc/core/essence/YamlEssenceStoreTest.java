package com.coremc.core.essence;

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
 * YamlEssenceStore: uuid -> {slayer, mining, farming, kills} round-trips
 * through essence-balances.yml with atomic writes, and a corrupt file starts
 * fresh instead of killing the plugin.
 */
class YamlEssenceStoreTest {

    @Test
    void roundTripsAllTypesAndKills(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("essence-balances.yml");
        final YamlEssenceStore store = new YamlEssenceStore(file, Logger.getLogger("test"));

        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final Map<UUID, EssenceProfile> saved = new LinkedHashMap<>();
        final EssenceProfile aliceProfile = new EssenceProfile();
        aliceProfile.slayer = 12;
        aliceProfile.mining = 345;
        aliceProfile.farming = 6_000_000; // long, not int
        aliceProfile.kills = 150_000;
        saved.put(alice, aliceProfile);
        final EssenceProfile bobProfile = new EssenceProfile();
        bobProfile.slayer = 1;
        saved.put(bob, bobProfile);
        store.saveAll(saved);

        assertTrue(Files.isRegularFile(file), "essence-balances.yml must exist after save");
        final Map<UUID, EssenceProfile> loaded = store.loadAll();
        assertEquals(2, loaded.size());
        assertEquals(12, loaded.get(alice).slayer);
        assertEquals(345, loaded.get(alice).mining);
        assertEquals(6_000_000, loaded.get(alice).farming);
        assertEquals(150_000, loaded.get(alice).kills);
        assertEquals(1, loaded.get(bob).slayer);
        assertEquals(0, loaded.get(bob).kills);
    }

    @Test
    void missingFileLoadsEmpty(@TempDir final Path dir) throws IOException {
        final YamlEssenceStore store =
                new YamlEssenceStore(dir.resolve("essence-balances.yml"), Logger.getLogger("test"));
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void corruptFileLoadsFresh(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("essence-balances.yml");
        Files.writeString(file, ":: not yaml [", StandardCharsets.UTF_8);
        final YamlEssenceStore store = new YamlEssenceStore(file, Logger.getLogger("test"));
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void nonUuidKeysAreSkippedAndGarbageNumbersReadAsZero(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("essence-balances.yml");
        final UUID good = UUID.randomUUID();
        final UUID sloppy = UUID.randomUUID();
        Files.writeString(file, """
                %s:
                  slayer: 7
                  mining: 0
                  farming: 0
                  kills: 9
                not-a-uuid:
                  slayer: 1
                %s:
                  slayer: not-a-number
                """.formatted(good, sloppy), StandardCharsets.UTF_8);
        final YamlEssenceStore store = new YamlEssenceStore(file, Logger.getLogger("test"));
        final Map<UUID, EssenceProfile> loaded = store.loadAll();
        assertEquals(2, loaded.size()); // "not-a-uuid" is skipped
        assertEquals(7, loaded.get(good).slayer);
        assertEquals(9, loaded.get(good).kills);
        assertEquals(0, loaded.get(sloppy).slayer); // unparseable numbers read as 0
    }

    @Test
    void savesAreAtomicTempThenMove(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("essence-balances.yml");
        final YamlEssenceStore store = new YamlEssenceStore(file, Logger.getLogger("test"));
        final Map<UUID, EssenceProfile> saved = new LinkedHashMap<>();
        saved.put(UUID.randomUUID(), new EssenceProfile());
        store.saveAll(saved);
        // no leftover .tmp file next to the store
        try (var stream = Files.list(dir)) {
            assertEquals(1, stream.count(), "only essence-balances.yml, no temp leftovers");
        }
    }
}
