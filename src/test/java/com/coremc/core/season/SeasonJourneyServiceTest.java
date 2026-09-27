package com.coremc.core.season;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SeasonJourneyServiceTest {

    @TempDir
    Path temp;

    @Test
    void awardsXpExactlyOnceAndPersistsAcrossRestart() throws Exception {
        final SeasonConfig config = activeConfig("season-one");
        final AtomicLong clock = new AtomicLong(config.season().startAt() + 1_000L);
        final SeasonJourneyService service = service(config, clock);
        service.load();
        final UUID player = UUID.randomUUID();

        assertTrue(service.addConfiguredXpOnce(player, SeasonXpSource.DAILY_QUEST, "daily:42:farm"));
        assertFalse(service.addConfiguredXpOnce(player, SeasonXpSource.DAILY_QUEST, "daily:42:farm"));
        assertEquals(100L, service.xp(player));
        assertEquals(2, service.level(player));

        service.shutdown();
        final SeasonJourneyService reloaded = service(config, clock);
        reloaded.load();
        assertEquals(100L, reloaded.xp(player));
        assertEquals(2, reloaded.level(player));
    }

    @Test
    void clampsCompletionAndArchivesOnlySeasonalStateOnNewSeason() throws Exception {
        final SeasonConfig first = activeConfig("season-one");
        final SeasonConfig second = activeConfig("season-two");
        final AtomicLong clock = new AtomicLong(first.season().startAt() + 1_000L);
        final SeasonJourneyService service = service(first, clock);
        service.load();
        final UUID player = UUID.randomUUID();

        assertTrue(service.addXp(player, 99_999L, SeasonXpSource.ADMIN, "finish"));
        final SeasonJourneyProfile completed = service.profile(player);
        assertEquals(first.maxXp(), completed.xp());
        assertEquals(first.maxLevel(), completed.highestLevel());
        assertTrue(completed.completed());
        assertTrue(completed.completedAt() > 0L);
        assertTrue(completed.history().containsKey("season-one"));

        service.reload(second);
        service.transitionAllKnownProfiles();
        final SeasonJourneyProfile transitioned = service.profile(player);
        assertEquals("season-two", transitioned.seasonId());
        assertEquals(0L, transitioned.xp());
        assertEquals(1, transitioned.highestLevel());
        assertFalse(transitioned.completed());
        assertTrue(transitioned.history().containsKey("season-one"));
    }

    @Test
    void claimReservesUnsupportedRewardsAndRepeatedClicksDoNotDuplicate() throws Exception {
        final SeasonConfig config = activeConfig("season-one");
        final AtomicLong clock = new AtomicLong(config.season().startAt() + 1_000L);
        final SeasonJourneyService service = service(config, clock);
        service.load();
        final UUID playerId = UUID.randomUUID();
        final Player player = player(playerId);

        assertEquals(SeasonJourneyService.ClaimResult.PENDING, service.claim(player, 1, "free"));
        final SeasonJourneyProfile profile = service.profile(playerId);
        assertTrue(profile.claimedFree().contains(1));
        assertEquals(1, profile.pendingRewards().size());

        assertEquals(SeasonJourneyService.ClaimResult.ALREADY_CLAIMED, service.claim(player, 1, "free"));
        assertEquals(1, profile.pendingRewards().size());
    }

    @Test
    void doesNotAwardOutsideAbsoluteSeasonWindow() throws Exception {
        final SeasonConfig config = activeConfig("season-one");
        final AtomicLong clock = new AtomicLong(config.season().endAt() + 1_000L);
        final SeasonJourneyService service = service(config, clock);
        service.load();
        final UUID player = UUID.randomUUID();

        assertFalse(service.addConfiguredXpOnce(player, SeasonXpSource.DAILY_QUEST, "after-end"));
        assertEquals(0L, service.xp(player));
    }

    private SeasonJourneyService service(final SeasonConfig config, final AtomicLong clock) {
        return new SeasonJourneyService(null, config,
                new YamlSeasonJourneyStore(temp.resolve("season-journey.yml"), Logger.getLogger("test")),
                null, null, SeasonRewardRegistry.defaults(null), SeasonPremiumAccess.none(),
                null, Logger.getLogger("test"), clock::get);
    }

    private SeasonConfig activeConfig(final String id) throws Exception {
        return SeasonConfigTest.parse(SeasonConfigTest.validYaml(id)
                .replace("2026-09-27T00:00:00Z", "1970-01-01T00:00:00Z")
                .replace("2026-11-15T00:00:00Z", "1970-01-02T00:00:00Z"));
    }

    private Player player(final UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> true;
                    case "getName" -> "Tester";
                    case "toString" -> "Tester";
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
