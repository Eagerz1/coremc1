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
 * The full limited-stock flow, headless: wall-clock open/close with
 * deduplicated warnings, exactly-once purchases with rollback, the
 * last-stock race, requirement fail-closed behaviour, restart
 * recovery and admin/schedule cooperation.
 */
class MarketServiceTest {

    private static final long HOUR = 3_600_000;
    private static final long MINUTE = 60_000;
    /** Slot start of the shipped grid (offset 15m) after epoch. */
    private static final long SLOT = 15 * MINUTE;

    @TempDir
    Path dir;

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

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
            events.add("purchase:" + offer.id() + ":" + buyer);
        }

        @Override
        public void bid(final UUID bidder, final String sessionId, final String lotId,
                        final long amount) {
            events.add("bid:" + lotId);
        }

        @Override
        public void win(final UUID winner, final String sessionId, final AuctionLotDef lot,
                        final long amount) {
            events.add("win:" + lot.id());
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

    private static final String YAML = """
            schedule:
              open-every-minutes: 360
              open-for-minutes: 30
              anchor-offset-minutes: 15
            warnings:
              opening-minutes: 10
              closing-minutes: [5, 1]
            selection:
              common-min: 1
              common-max: 1
              rare: 2
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
              gen-core:
                pool: rare
                reward:
                  type: item
                  id: generator_core
                  material: LODESTONE
                  display: "&6Generator Core"
                price:
                  money: 750000
                stock: 1
                per-player: 1
              gated:
                pool: rare
                reward:
                  type: item
                  id: core_fragment
                  material: ECHO_SHARD
                  display: "&5Core Fragment"
                price:
                  money: 100000
                stock: 5
                per-player: 1
                requirements:
                  - "collection:mystic"
            auction:
              times: []
              lots-per-session-min: 1
              lots-per-session-max: 1
              lot-duration-seconds: 60
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
            """;

    private BlackMarketConfig config;
    private MarketState state;
    private EconomyService economy;
    private PendingRewards pending;
    private MemoryPending pendingStore;
    private Recorder recorder;
    private MarketTestSupport.Clock clock;
    private TransactionLog transactions;

    private MarketService service(final MarketBank bankOverride) {
        config = new BlackMarketConfig(null);
        final org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.loadFromString(YAML);
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
        if (pendingStore == null) {
            pendingStore = new MemoryPending();
        }
        try {
            pending = new PendingRewards(pendingStore, MarketTestSupport.LOGGER);
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
        recorder = recorder == null ? new Recorder() : recorder;
        clock = clock == null ? new MarketTestSupport.Clock(SLOT + MINUTE) : clock;
        transactions = new TransactionLog(dir.resolve("txn.log"), MarketTestSupport.LOGGER);
        final MarketRequirements requirements = new MarketRequirements();
        requirements.register("collection", (player, value) ->
                player.equals(MarketTestSupport.RIVAL) && value.equals("mystic"));
        final MarketBank bank = bankOverride != null ? bankOverride
                : new MarketBank(economy, null, null);
        return new MarketService(config, state, bank, requirements, pending, null,
                transactions, null, recorder, recorder, new Random(42), () -> clock.now,
                MarketTestSupport.LOGGER);
    }

    private MarketService service() {
        return service(null);
    }

    // ------------------------------------------------------------------
    // schedule + warnings
    // ------------------------------------------------------------------

    @Test
    void heartbeatOpensAndClosesOnTheWallClock() {
        final MarketService market = service();
        clock.now = SLOT - 20 * MINUTE;
        market.heartbeat(clock.now);
        assertFalse(state.isOpen(clock.now), "too early");
        clock.now = SLOT;
        market.heartbeat(clock.now);
        assertTrue(state.isOpen(clock.now), "grid slot opens");
        assertEquals("rot-" + SLOT, state.rotationId());
        assertEquals(3, state.activeOffers().size(), "1 common + 2 rare selected");
        assertTrue(recorder.announcements.contains("market.open"));
        clock.now = SLOT + 30 * MINUTE;
        market.heartbeat(clock.now);
        assertFalse(state.isOpen(clock.now), "window over");
        assertTrue(recorder.announcements.contains("market.closed"));
    }

    @Test
    void warningsFireOnceAndOnlyNearTheirDueTime() {
        final MarketService market = service();
        // opening warning: due at SLOT-10m
        clock.now = SLOT - 10 * MINUTE;
        market.heartbeat(clock.now);
        market.heartbeat(clock.now + 1_000); // second heartbeat in the window
        assertEquals(1, count(recorder.announcements, "market.opening-warning"),
                "deduplicated");
        // a heartbeat far past the due time must NOT replay the warning
        recorder.announcements.clear();
        clock.now = SLOT - 4 * MINUTE;
        market.heartbeat(clock.now);
        assertEquals(0, count(recorder.announcements, "market.opening-warning"),
                "a restart after the due time never replays it");
        // closing warnings at close-5m and close-1m
        clock.now = SLOT;
        market.heartbeat(clock.now);
        clock.now = SLOT + 25 * MINUTE;
        market.heartbeat(clock.now);
        clock.now = SLOT + 29 * MINUTE;
        market.heartbeat(clock.now);
        assertEquals(2, count(recorder.announcements, "market.closing-warning"));
    }

    private static int count(final List<String> list, final String key) {
        return (int) list.stream().filter(key::equals).count();
    }

    @Test
    void restartInsideTheWindowKeepsTheRotationAndStock() {
        MarketService market = service();
        clock.now = SLOT;
        market.heartbeat(clock.now);
        economy.deposit(MarketTestSupport.BUYER, 800_000);
        assertEquals(MarketService.PurchaseResult.OK, market.purchase(MarketTestSupport.BUYER,
                null, state.rotationId(), "gen-core"));

        // "restart": fresh state + service over the same files, 10m later
        state = new MarketState(dir.resolve("black-market-data.yml"), 50,
                MarketTestSupport.LOGGER);
        state.load();
        market = service();
        clock.now = SLOT + 10 * MINUTE;
        market.recover();
        market.heartbeat(clock.now);
        assertEquals("rot-" + SLOT, state.rotationId(), "same window, same rotation");
        assertEquals(0, state.offer("gen-core").stockLeft(), "stock survived the restart");
        assertEquals(1, state.purchased("gen-core", MarketTestSupport.BUYER),
                "per-player counts survived");
        assertEquals(MarketService.PurchaseResult.LIMIT_REACHED, market.purchase(
                MarketTestSupport.BUYER, null, state.rotationId(), "gen-core"));
    }

    @Test
    void restartAfterTheWindowClosesTheExpiredRotation() {
        MarketService market = service();
        clock.now = SLOT;
        market.heartbeat(clock.now);
        assertTrue(state.isOpen(clock.now));

        state = new MarketState(dir.resolve("black-market-data.yml"), 50,
                MarketTestSupport.LOGGER);
        state.load();
        market = service();
        clock.now = SLOT + 2 * HOUR;
        market.recover();
        assertNull(state.rotationId(), "the offline-expired rotation closed on boot");
        market.heartbeat(clock.now);
        assertFalse(state.isOpen(clock.now), "and stays closed until the next grid slot");
        assertTrue(market.secondsUntilOpen(clock.now) > 0);
    }

    @Test
    void adminOperationsCooperateWithTheSchedule() {
        final MarketService market = service();
        clock.now = SLOT - 2 * HOUR;
        assertTrue(market.adminOpen(), "manual open outside the grid");
        assertTrue(state.isOpen(clock.now));
        assertTrue(state.rotationId().startsWith("manual-"));
        assertFalse(market.adminOpen(), "already open");
        assertTrue(market.adminRotate(), "manual rotate rolls a fresh rotation");
        assertTrue(market.adminClose());
        assertFalse(market.adminClose(), "already closed");

        // closing a GRID window early suppresses ITS reopen only
        clock.now = SLOT + MINUTE;
        market.heartbeat(clock.now);
        assertEquals("rot-" + SLOT, state.rotationId());
        assertTrue(market.adminClose());
        market.heartbeat(clock.now + 1_000);
        assertNull(state.rotationId(), "the closed grid window stays closed");
        // ... but the NEXT grid window opens normally
        clock.now = SLOT + 6 * HOUR;
        market.heartbeat(clock.now);
        assertEquals("rot-" + (SLOT + 6 * HOUR), state.rotationId());
    }

    // ------------------------------------------------------------------
    // purchases
    // ------------------------------------------------------------------

    private MarketService openMarket() {
        final MarketService market = service();
        clock.now = SLOT;
        market.heartbeat(clock.now);
        clock.now = SLOT + MINUTE;
        return market;
    }

    @Test
    void aPurchaseDebitsDeliversAndRecordsExactlyOnce() throws IOException {
        final MarketService market = openMarket();
        economy.deposit(MarketTestSupport.BUYER, 800_000);
        assertEquals(MarketService.PurchaseResult.OK, market.purchase(MarketTestSupport.BUYER,
                null, state.rotationId(), "gen-core"));
        assertEquals(50_000, economy.balance(MarketTestSupport.BUYER), 0.001,
                "debited exactly the rotation price");
        assertEquals(1, pending.grantCount(MarketTestSupport.BUYER),
                "the reward sits safely in the store's pending pipeline");
        assertEquals(1, state.sales().size());
        assertEquals("gen-core", state.sales().get(0).offerId());
        assertEquals(List.of("purchase:gen-core:" + MarketTestSupport.BUYER), recorder.events);
        final String log = Files.readString(dir.resolve("txn.log"));
        assertTrue(log.contains("MARKET_BUY") && log.contains("offer=gen-core"));
    }

    @Test
    void theLastUnitSellsExactlyOnce() {
        final MarketService market = openMarket();
        economy.deposit(MarketTestSupport.BUYER, 800_000);
        economy.deposit(MarketTestSupport.RIVAL, 800_000);
        assertEquals(MarketService.PurchaseResult.OK, market.purchase(MarketTestSupport.BUYER,
                null, state.rotationId(), "gen-core"));
        assertEquals(MarketService.PurchaseResult.OUT_OF_STOCK, market.purchase(
                MarketTestSupport.RIVAL, null, state.rotationId(), "gen-core"));
        assertEquals(800_000, economy.balance(MarketTestSupport.RIVAL), 0.001,
                "the loser pays nothing");
        assertEquals(0, pending.grantCount(MarketTestSupport.RIVAL));
        assertEquals(1, state.sales().size());
    }

    @Test
    void everyRefusalCostsNothing() {
        final MarketService market = openMarket();
        final UUID buyer = MarketTestSupport.BUYER;
        assertEquals(MarketService.PurchaseResult.UNAFFORDABLE,
                market.purchase(buyer, null, state.rotationId(), "bait"));
        assertEquals(MarketService.PurchaseResult.STALE_ROTATION,
                market.purchase(buyer, null, "rot-ancient", "bait"));
        assertEquals(MarketService.PurchaseResult.UNKNOWN_OFFER,
                market.purchase(buyer, null, state.rotationId(), "nope"));
        economy.deposit(buyer, 200_000);
        assertEquals(MarketService.PurchaseResult.REQUIREMENTS,
                market.purchase(buyer, null, state.rotationId(), "gated"),
                "an unmet requirement fails closed");
        assertEquals(200_000, economy.balance(buyer), 0.001);
        assertEquals(0, pending.grantCount(buyer));
        assertEquals(8, state.offer("bait").stockLeft());
        assertEquals(5, state.offer("gated").stockLeft());
        // the rival HAS the collection unlock (test registry) and may buy
        economy.deposit(MarketTestSupport.RIVAL, 200_000);
        assertEquals(MarketService.PurchaseResult.OK,
                market.purchase(MarketTestSupport.RIVAL, null, state.rotationId(), "gated"));
        // market closed refuses too
        clock.now = SLOT + HOUR;
        assertEquals(MarketService.PurchaseResult.CLOSED,
                market.purchase(buyer, null, "rot-" + SLOT, "bait"));
    }

    @Test
    void aFailedDebitRollsBackStockLimitAndPending() {
        // a bank that approves affordability but refuses the debit —
        // the impossible race, forced
        final MarketBank broken = new MarketBank(MarketTestSupport.economy(0), null, null) {
            @Override
            public boolean canAfford(final UUID player, final MarketCost cost) {
                return true;
            }

            @Override
            public boolean debit(final UUID player, final MarketCost cost, final String detail) {
                return false;
            }
        };
        final MarketService market = service(broken);
        clock.now = SLOT;
        market.heartbeat(clock.now);
        assertEquals(MarketService.PurchaseResult.DEBIT_FAILED, market.purchase(
                MarketTestSupport.BUYER, null, state.rotationId(), "gen-core"));
        assertEquals(1, state.offer("gen-core").stockLeft(), "reservation rolled back");
        assertEquals(0, state.purchased("gen-core", MarketTestSupport.BUYER));
        assertEquals(0, pending.grantCount(MarketTestSupport.BUYER), "pending unwound");
        assertTrue(state.sales().isEmpty());
        assertTrue(recorder.events.isEmpty(), "no event for a failed purchase");
    }

    @Test
    void perPlayerLimitsAllowExactlyTheConfiguredCount() {
        final MarketService market = openMarket();
        economy.deposit(MarketTestSupport.BUYER, 1_000_000);
        assertEquals(MarketService.PurchaseResult.OK,
                market.purchase(MarketTestSupport.BUYER, null, state.rotationId(), "bait"));
        assertEquals(MarketService.PurchaseResult.OK,
                market.purchase(MarketTestSupport.BUYER, null, state.rotationId(), "bait"));
        assertEquals(MarketService.PurchaseResult.LIMIT_REACHED,
                market.purchase(MarketTestSupport.BUYER, null, state.rotationId(), "bait"));
        assertEquals(6, state.offer("bait").stockLeft());
        assertEquals(900_000, economy.balance(MarketTestSupport.BUYER), 0.001,
                "exactly two prices paid");
    }

    @Test
    void statusLinesAreHonest() {
        final MarketService market = service();
        clock.now = SLOT - 10 * MINUTE;
        assertTrue(market.statusLine(clock.now).startsWith("closed"));
        clock.now = SLOT;
        market.heartbeat(clock.now);
        assertTrue(market.statusLine(clock.now).startsWith("open"));
        assertNotNull(state.rotationId());
        assertEquals(30 * 60, market.secondsUntilClose(clock.now));
    }
}
