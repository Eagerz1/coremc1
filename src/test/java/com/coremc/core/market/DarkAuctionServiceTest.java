package com.coremc.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.reward.PendingRewards;
import com.coremc.core.reward.PendingStore;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.store.TransactionLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Dark Auction session lifecycle, headless: schedule keys,
 * sequential lots, settlement through the real EconomyService
 * (winner charged exactly once, fallback on a broke winner, cancel
 * with nobody charged), overlap refusal, restart semantics and
 * history.
 */
class DarkAuctionServiceTest {

    private static final UUID ALICE = MarketTestSupport.BUYER;
    private static final UUID BOB = MarketTestSupport.RIVAL;

    @TempDir
    Path dir;

    private static final String YAML = """
            schedule:
              open-every-minutes: 360
              open-for-minutes: 30
            selection:
              common-min: 1
              common-max: 1
              rare: 0
              epic: 0
              legendary-chance: 0
              cosmetic-chance: 0
              seasonal-chance: 0
            offers:
              bait:
                pool: common
                reward:
                  type: item
                  id: special_bait
                  material: TROPICAL_FISH
                  display: "&bSpecial Bait"
                price:
                  money: 50000
                stock: 8
                per-player: 2
            auction:
              times: ["19:30"]
              lots-per-session-min: 2
              lots-per-session-max: 2
              lot-duration-seconds: 60
              break-seconds: 10
              anti-snipe:
                threshold-seconds: 10
                extension-seconds: 10
                max-extension-seconds: 30
              bid-buttons: ["add:10000"]
              lots:
                core-lot:
                  rarity: epic
                  reward:
                    type: item
                    id: generator_core
                    material: LODESTONE
                    display: "&6Generator Core"
                  starting-bid: 250000
                  min-increment: 25000
                tag-lot:
                  rarity: cosmetic
                  reward:
                    type: item
                    id: tag_shadow
                    material: NAME_TAG
                    display: "&8Shadow Tag"
                  starting-bid: 100000
                  min-increment: 10000
            """;

    private static final class Recorder implements MarketAnnouncer, MarketEvents {
        final List<String> announcements = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        @Override
        public void announce(final String key, final Map<String, String> placeholders) {
            announcements.add(key);
        }

        @Override
        public void purchase(final UUID buyer, final String rotationId, final MarketOffer offer,
                             final MarketCost price) {
            events.add("purchase");
        }

        @Override
        public void bid(final UUID bidder, final String sessionId, final String lotId,
                        final long amount) {
            events.add("bid:" + lotId + ":" + bidder);
        }

        @Override
        public void win(final UUID winner, final String sessionId, final AuctionLotDef lot,
                        final long amount) {
            events.add("win:" + lot.id() + ":" + winner + ":" + amount);
        }
    }

    private static final class MemoryPending implements PendingStore {
        private Map<UUID, List<PendingRewards.PendingTxn>> data = new LinkedHashMap<>();

        @Override
        public Map<UUID, List<PendingRewards.PendingTxn>> loadAll() {
            final Map<UUID, List<PendingRewards.PendingTxn>> copy = new LinkedHashMap<>();
            data.forEach((key, value) -> copy.put(key, new ArrayList<>(value)));
            return copy;
        }

        @Override
        public void saveAll(final Map<UUID, List<PendingRewards.PendingTxn>> pending) {
            data = new LinkedHashMap<>();
            pending.forEach((key, value) -> data.put(key, new ArrayList<>(value)));
        }
    }

    private BlackMarketConfig config;
    private MarketState state;
    private EconomyService economy;
    private PendingRewards pending;
    private Recorder recorder;
    private MarketTestSupport.Clock clock;

    private DarkAuctionService auction() {
        config = new BlackMarketConfig(null);
        final org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.loadFromString(YAML.replace("timezone-placeholder", "UTC"));
            // pin the zone so date keys are deterministic
            yaml.set("schedule.timezone", "UTC");
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(yaml, java.util.Set.of("vote"), java.util.Set.of("core"));
        if (state == null) {
            state = new MarketState(dir.resolve("black-market-data.yml"), 50,
                    MarketTestSupport.LOGGER);
            state.load();
        }
        if (economy == null) {
            economy = MarketTestSupport.economy(0);
        }
        try {
            pending = new PendingRewards(new MemoryPending(), MarketTestSupport.LOGGER);
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
        recorder = recorder == null ? new Recorder() : recorder;
        clock = clock == null ? new MarketTestSupport.Clock(1_000_000) : clock;
        return new DarkAuctionService(null, config, state, economy, pending, null,
                new TransactionLog(dir.resolve("txn.log"), MarketTestSupport.LOGGER), null,
                recorder, recorder, new Random(7), () -> clock.now, MarketTestSupport.LOGGER);
    }

    /** Ticks past the start-break so lot 1 is live. */
    private DarkAuctionService startedAuction() {
        final DarkAuctionService service = auction();
        assertTrue(service.start("test-session", "test"));
        clock.now += 3_000;
        service.tick(clock.now);
        assertNotNull(service.engine(), "lot 1 is on the floor");
        return service;
    }

    @Test
    void nextSessionUsesTheZoneAndSkipsTheLastKey() {
        final DarkAuctionService service = auction();
        // 2026-09-27 10:00 UTC
        final long morning = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli();
        final Map.Entry<String, Long> next = service.nextSession(morning);
        assertEquals("2026-09-27 19:30", next.getKey());
        assertEquals(java.time.Instant.parse("2026-09-27T19:30:00Z").toEpochMilli(),
                next.getValue());
        // after that key ran, the same evening is skipped → tomorrow
        state.lastAuctionSession("2026-09-27 19:30");
        assertEquals("2026-09-28 19:30", service.nextSession(morning).getKey());
    }

    @Test
    void scheduledSessionsStartOnceViaTheTick() {
        final DarkAuctionService service = auction();
        final long start = java.time.Instant.parse("2026-09-27T19:30:00Z").toEpochMilli();
        clock.now = start - 60_000;
        service.tick(clock.now);
        assertFalse(service.active(), "not yet");
        clock.now = start + 1_000;
        service.tick(clock.now);
        assertTrue(service.active(), "the schedule started the session");
        assertEquals("2026-09-27 19:30", state.lastAuctionSession());
    }

    @Test
    void sessionsRefuseToOverlap() {
        final DarkAuctionService service = startedAuction();
        assertFalse(service.start("another", "test"), "no overlapping sessions");
    }

    @Test
    void winnerIsChargedExactlyOnceAndPaidLotIsPending() throws IOException {
        final DarkAuctionService service = startedAuction();
        economy.deposit(ALICE, 1_000_000);
        economy.deposit(BOB, 1_000_000);
        assertEquals(AuctionEngine.BidResult.OK, service.bid(ALICE, 250_000));
        assertEquals(AuctionEngine.BidResult.OK, service.bid(BOB, 300_000));
        assertEquals(AuctionEngine.BidResult.TOO_LOW, service.bid(ALICE, 310_000));
        assertEquals(AuctionEngine.BidResult.CANNOT_AFFORD, service.bid(ALICE, 2_000_000));
        assertEquals(1_000_000, economy.balance(ALICE), 0.001, "bidding never charges");

        clock.now += 61_000;
        service.tick(clock.now); // settles lot 1
        assertEquals(700_000, economy.balance(BOB), 0.001, "the winner paid the bid");
        assertEquals(1_000_000, economy.balance(ALICE), 0.001, "the loser paid NOTHING");
        assertEquals(1, pending.grantCount(BOB), "the lot waits in pending delivery");
        assertEquals(1, state.lotHistory().size());
        assertEquals(BOB, state.lotHistory().get(0).winner());
        assertEquals(300_000, state.lotHistory().get(0).amount());
        assertTrue(recorder.events.contains("win:" + state.lotHistory().get(0).lotId()
                + ":" + BOB + ":300000"));
        final String log = Files.readString(dir.resolve("txn.log"));
        assertTrue(log.contains("AUCTION_WIN"));
        // ticking on cannot settle the same lot twice
        service.tick(clock.now + 1_000);
        assertEquals(700_000, economy.balance(BOB), 0.001);
        assertEquals(1, state.lotHistory().size());
    }

    @Test
    void brokeWinnerFallsBackToThePreviousBidder() {
        final DarkAuctionService service = startedAuction();
        economy.deposit(ALICE, 400_000);
        economy.deposit(BOB, 400_000);
        assertEquals(AuctionEngine.BidResult.OK, service.bid(ALICE, 250_000));
        assertEquals(AuctionEngine.BidResult.OK, service.bid(BOB, 300_000));
        // Bob spends his money elsewhere before the hammer falls
        economy.withdraw(BOB, 350_000);
        clock.now += 61_000;
        service.tick(clock.now);
        assertEquals(ALICE, state.lotHistory().get(0).winner(), "fallback to Alice");
        assertEquals(250_000, state.lotHistory().get(0).amount());
        assertEquals(150_000, economy.balance(ALICE), 0.001);
        assertEquals(50_000, economy.balance(BOB), 0.001, "the broke leader pays nothing");
        assertTrue(economy.balance(BOB) >= 0, "never negative");
    }

    @Test
    void unsoldLotsCancelWithNobodyCharged() throws IOException {
        final DarkAuctionService service = startedAuction();
        clock.now += 61_000;
        service.tick(clock.now); // no bids at all
        assertNull(state.lotHistory().get(0).winner());
        assertTrue(recorder.announcements.contains("market.auction-unsold"));
        assertTrue(Files.readString(dir.resolve("txn.log")).contains("AUCTION_PASS"));
    }

    @Test
    void sessionsRunTheirLotsSequentiallyThenEnd() {
        final DarkAuctionService service = startedAuction();
        final String firstLot = service.currentLot().id();
        assertEquals(1, service.lotsRemaining());
        clock.now += 61_000;
        service.tick(clock.now); // settle lot 1 → break
        assertNull(service.engine());
        clock.now += 10_000;
        service.tick(clock.now); // break over → lot 2
        assertNotNull(service.engine());
        assertFalse(service.currentLot().id().equals(firstLot), "a different lot");
        clock.now += 61_000;
        service.tick(clock.now); // settle lot 2 → break
        clock.now += 10_000;
        service.tick(clock.now); // break over → session ends
        assertFalse(service.active());
        assertTrue(recorder.announcements.contains("market.auction-end"));
        assertEquals(2, state.lotHistory().size());
    }

    @Test
    void adminStopCancelsTheLiveLotSafely() throws IOException {
        final DarkAuctionService service = startedAuction();
        economy.deposit(ALICE, 500_000);
        assertEquals(AuctionEngine.BidResult.OK, service.bid(ALICE, 250_000));
        service.end("admin stop");
        assertFalse(service.active());
        assertEquals(500_000, economy.balance(ALICE), 0.001,
                "a cancelled lot charges nobody");
        assertEquals(0, pending.grantCount(ALICE));
        assertTrue(Files.readString(dir.resolve("txn.log")).contains("AUCTION_CANCELLED"));
        assertNull(state.lotHistory().get(0).winner());
        // shutdown() with no session is harmless
        service.shutdown();
    }

    @Test
    void restartNeverReconstructsAnUncertainSettlement() {
        final DarkAuctionService service = startedAuction();
        economy.deposit(ALICE, 500_000);
        service.bid(ALICE, 250_000);
        // "restart": shutdown cancels; a NEW service starts clean
        service.shutdown();
        assertEquals(500_000, economy.balance(ALICE), 0.001, "nobody debited");
        recorder = null;
        clock.now += 5_000;
        final DarkAuctionService rebooted = auction();
        assertFalse(rebooted.active());
        assertEquals("test-session", state.lastAuctionSession(),
                "the persisted key blocks a same-slot re-run");
        assertNull(rebooted.engine());
    }

    @Test
    void bidEventsFireOnlyForValidBids() {
        final DarkAuctionService service = startedAuction();
        economy.deposit(ALICE, 300_000);
        service.bid(ALICE, 100); // too low — no event
        service.bid(ALICE, 250_000);
        assertEquals(1, recorder.events.stream().filter(e -> e.startsWith("bid:")).count());
    }

    @Test
    void bidButtonsComputeTheNextValidAmount() {
        final DarkAuctionService service = startedAuction();
        final BlackMarketConfig.BidButton add = config.bidButtons().get(0); // add:10000
        // no bids yet: the button bids at least the starting bid
        assertEquals(260_000, MarketGui.nextBidFor(add, service.engine()),
                "starting 250k + 10k");
        economy.deposit(ALICE, 1_000_000);
        service.bid(ALICE, 250_000);
        // 250k current + 10k < min next (275k) → snaps to the minimum
        assertEquals(275_000, MarketGui.nextBidFor(add, service.engine()));
        final BlackMarketConfig.BidButton percent = new BlackMarketConfig.BidButton(true, 10);
        assertEquals(275_000, MarketGui.nextBidFor(percent, service.engine()));
        service.bid(ALICE, 500_000);
        assertEquals(550_000, MarketGui.nextBidFor(percent, service.engine()),
                "+10% of 500k");
    }
}
