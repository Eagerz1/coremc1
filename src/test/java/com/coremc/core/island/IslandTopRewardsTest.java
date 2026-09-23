package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.tebex.GiftcardStore;
import com.coremc.core.tebex.MockTebexServer;
import com.coremc.core.tebex.TebexClient;
import com.coremc.core.tebex.TebexConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Island top season rewards: the pure payout table, the once-per-season
 * guard, the Tebex-off skip and a full payout against the stand-in
 * Tebex server (cards created, linked to the island owners, state
 * persisted).
 */
final class IslandTopRewardsTest {

    private static Island island(final String owner, final long createdAt,
                                 final UUID... members) {
        final Island island = new Island(UUID.randomUUID(), UUID.randomUUID(), owner,
                "coremc_islands", 0, 0, 64, 0, 100, "default", createdAt, 0, 64, 0, 0f, 0f);
        for (final UUID member : members) {
            island.addMember(member);
        }
        return island;
    }

    @TempDir
    Path tempDir;

    private MockTebexServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockTebexServer();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    /** Headless rewards service: no scheduler, no online players. */
    private IslandTopRewards rewards(final List<Island> standings, final IslandPointsService points,
                                     final TebexConfig config, final GiftcardStore store,
                                     final Path stateFile) {
        return new IslandTopRewards(null, () -> standings, points, config,
                new TebexClient(config), store, null, Logger.getLogger("test"), stateFile) {
            @Override
            protected void hopToMain(final Runnable task) {
                task.run();
            }

            @Override
            protected Player onlineOwner(final UUID owner) {
                return null;
            }
        };
    }

    @Test
    void winnersTablePaysEachCategoryTopPlaces() {
        final Island soloA = island("SoloA", 1);
        final Island soloB = island("SoloB", 2);
        final Island duoA = island("DuoA", 3, UUID.randomUUID());
        final Island teamA = island("TeamA", 4, UUID.randomUUID(), UUID.randomUUID());
        final Island teamB = island("TeamB", 5, UUID.randomUUID(), UUID.randomUUID());
        final java.util.Map<String, Double> board = java.util.Map.of(
                "SoloA", 500.0, "SoloB", 300.0, "DuoA", 90.0, "TeamA", 50.0, "TeamB", 40.0);
        final List<IslandTopRewards.Reward> table = IslandTopRewards.winners(
                List.of(soloB, teamB, soloA, teamA, duoA),
                island -> board.get(island.ownerName()));

        assertEquals(5, table.size());
        assertEquals("SoloA", table.get(0).island().ownerName());
        assertEquals(IslandTop.Category.SOLOS, table.get(0).category());
        assertEquals(1, table.get(0).rank());
        assertEquals(100.0, table.get(0).amount());
        assertEquals(75.0, table.get(1).amount());
        assertEquals("SoloB", table.get(1).island().ownerName());
        assertEquals(IslandTop.Category.DUOS, table.get(2).category());
        assertEquals(100.0, table.get(2).amount());
        assertEquals("TeamA", table.get(3).island().ownerName());
        assertEquals(100.0, table.get(3).amount());
        assertEquals(75.0, table.get(4).amount());
    }

    @Test
    void winnersSkipsEmptyCategories() {
        final List<IslandTopRewards.Reward> table = IslandTopRewards.winners(
                List.of(island("Only", 1)), island -> 10.0);
        assertEquals(1, table.size());
        assertEquals(IslandTop.Category.SOLOS, table.get(0).category());
        assertEquals(100.0, table.get(0).amount());
    }

    @Test
    void tebexOffSkipsThePayoutWithoutMarkingTheSeasonPaid() throws Exception {
        final Island solo = island("Only", 1);
        final IslandPointsService points = new IslandPointsService(tempDir.resolve("points.yml"));
        points.add(solo, 500.0);
        final TebexConfig off = new TebexConfig("", server.baseUrl());
        final IslandTopRewards rewards = rewards(List.of(solo), points, off,
                new GiftcardStore(tempDir.resolve("giftcards.yml")),
                tempDir.resolve("island-rewards.yml"));

        rewards.payOut(1);

        assertEquals(0, rewards.lastPaidSeason(), "nothing is marked paid while Tebex is off");
        assertEquals(0, server.requests());
    }

    @Test
    void alreadyPaidSeasonIsNeverPaidTwice() throws Exception {
        final Island solo = island("Only", 1);
        final IslandPointsService points = new IslandPointsService(tempDir.resolve("points.yml"));
        points.add(solo, 500.0);
        final Path state = tempDir.resolve("island-rewards.yml");
        Files.writeString(state, "last-paid-season: 2\n");
        final IslandTopRewards rewards = rewards(List.of(solo), points,
                new TebexConfig("secret", server.baseUrl()),
                new GiftcardStore(tempDir.resolve("giftcards.yml")), state);

        rewards.load();
        assertEquals(2, rewards.lastPaidSeason());
        rewards.payOut(2);
        rewards.payOut(1);
        assertEquals(0, server.requests(), "a paid season never pays again");
    }

    @Test
    void fullPayoutCreatesCardsAndLinksThemToTheOwners() throws Exception {
        final Island solo = island("SoloOwner", 1);
        final Island duo = island("DuoOwner", 2, UUID.randomUUID());
        final IslandPointsService points = new IslandPointsService(tempDir.resolve("points.yml"));
        points.add(solo, 500.0);
        points.add(duo, 90.0);
        final GiftcardStore store = new GiftcardStore(tempDir.resolve("giftcards.yml"));
        store.load();
        final Path state = tempDir.resolve("island-rewards.yml");
        final IslandTopRewards rewards = rewards(List.of(duo, solo), points,
                new TebexConfig("secret", server.baseUrl()), store, state);

        server.respond(200, """
                {"data": {"id": 31, "code": "GC-SOLO-1",
                          "balance": {"starting": "100.00", "remaining": "100.00", "currency": "GBP"},
                          "note": "Island top #1 Solos", "void": false}}
                """);
        server.respond(200, """
                {"data": {"id": 32, "code": "GC-DUO-1",
                          "balance": {"starting": "100.00", "remaining": "100.00", "currency": "GBP"},
                          "note": "Island top #1 Duos", "void": false}}
                """);

        rewards.payOut(1);
        rewards.currentPayout().get(10, TimeUnit.SECONDS);

        assertEquals(1, rewards.lastPaidSeason());
        assertEquals(2, server.requests());
        assertEquals("GC-SOLO-1", store.codeOf(solo.owner()));
        assertEquals("GC-DUO-1", store.codeOf(duo.owner()));
        assertEquals("/gift-cards", server.paths().get(0));
        assertEquals("/gift-cards", server.paths().get(1));
        assertTrue(server.bodies().get(0).contains("Island top #1 Solos"),
                "note names the place and category: " + server.bodies().get(0));
        assertTrue(server.bodies().get(1).contains("Island top #1 Duos"));
        assertTrue(Files.readString(state).contains("last-paid-season: 1"),
                "the paid season is persisted: " + Files.readString(state));

        // paying the same season again is a no-op
        rewards.payOut(1);
        assertEquals(2, server.requests());
    }

    @Test
    void stateFileSurvivesReload() throws Exception {
        final IslandPointsService points = new IslandPointsService(tempDir.resolve("points.yml"));
        final Path state = tempDir.resolve("island-rewards.yml");
        final IslandTopRewards rewards = rewards(List.of(), points,
                new TebexConfig("secret", server.baseUrl()),
                new GiftcardStore(tempDir.resolve("giftcards.yml")), state);
        Files.writeString(state, "last-paid-season: 7\n");
        rewards.load();
        assertEquals(7, rewards.lastPaidSeason());
    }
}
