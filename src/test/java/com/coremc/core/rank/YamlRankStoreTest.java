package com.coremc.core.rank;

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
 * Rank persistence round-trips: the season number and every player's
 * rank, River keys and payout season survive a save/load cycle; a
 * corrupt file starts fresh instead of crashing.
 */
class YamlRankStoreTest {

    private static final Logger LOGGER = Logger.getLogger(YamlRankStoreTest.class.getName());

    @TempDir
    Path dir;

    @Test
    void seasonAndPlayersRoundTrip() throws IOException {
        final Path file = dir.resolve("ranks-data.yml");
        final YamlRankStore store = new YamlRankStore(file, LOGGER);

        final UUID steve = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID notch = UUID.fromString("00000000-0000-0000-0000-000000000002");
        final Map<UUID, YamlRankStore.PlayerRank> players = new LinkedHashMap<>();
        players.put(steve, new YamlRankStore.PlayerRank("Steve", "core-plus-plus", 19, 2));
        players.put(notch, new YamlRankStore.PlayerRank("Notch", "core", 2, 2));
        store.save(2, players);

        assertTrue(Files.isRegularFile(file), "ranks-data.yml is written eagerly");
        final YamlRankStore.StoreData loaded = store.load();
        assertEquals(2, loaded.season);
        assertEquals(2, loaded.players.size());
        assertEquals("core-plus-plus", loaded.players.get(steve).rankId);
        assertEquals(19, loaded.players.get(steve).riverKeys);
        assertEquals(2, loaded.players.get(steve).lastPayout);
        assertEquals("Notch", loaded.players.get(notch).name);
        assertEquals("core", loaded.players.get(notch).rankId);
    }

    @Test
    void missingFileLoadsSeasonOneAndNoPlayers() throws IOException {
        final YamlRankStore store = new YamlRankStore(dir.resolve("ranks-data.yml"), LOGGER);
        final YamlRankStore.StoreData data = store.load();
        assertEquals(1, data.season);
        assertTrue(data.players.isEmpty());
    }

    @Test
    void corruptFileStartsFresh() throws IOException {
        final Path file = dir.resolve("ranks-data.yml");
        Files.writeString(file, "ranks: [this: is: not: valid: {", StandardCharsets.UTF_8);
        final YamlRankStore store = new YamlRankStore(file, LOGGER);
        final YamlRankStore.StoreData data = store.load();
        assertEquals(1, data.season);
        assertTrue(data.players.isEmpty(), "corrupt rank data never crashes the plugin");
    }

    @Test
    void badPlayerIdsAreSkipped() throws IOException {
        final Path file = dir.resolve("ranks-data.yml");
        Files.writeString(file, String.join("\n",
                "season: 7",
                "players:",
                "  not-a-uuid:",
                "    rank: core",
                "  00000000-0000-0000-0000-000000000009:",
                "    name: Alex",
                "    rank: core-plus",
                "    river-keys: 5",
                "    last-payout: 6"),
                StandardCharsets.UTF_8);
        final YamlRankStore store = new YamlRankStore(file, LOGGER);
        final YamlRankStore.StoreData data = store.load();
        assertEquals(7, data.season);
        assertEquals(1, data.players.size(), "only the well-formed entry loads");
        final YamlRankStore.PlayerRank alex = data.players
                .get(UUID.fromString("00000000-0000-0000-0000-000000000009"));
        assertEquals("core-plus", alex.rankId);
        assertEquals(5, alex.riverKeys);
        assertEquals(6, alex.lastPayout);
    }
}
