package com.coremc.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The persisted market state: atomic global stock, per-player
 * limits, restart-safe rotations (no reset, no duplicate), bounded
 * history and corrupt-file recovery.
 */
class MarketStateTest {

    @TempDir
    Path dir;

    private MarketState fresh() {
        final MarketState state = new MarketState(dir.resolve("black-market-data.yml"), 50,
                MarketTestSupport.LOGGER);
        state.load();
        return state;
    }

    private static List<RotationSelector.Selected> selection(final int stock) {
        final MarketOffer offer = MarketTestSupport.offer("gen-core", MarketRarity.EPIC,
                750_000, stock, 1);
        return List.of(new RotationSelector.Selected(offer, offer.cost().resolve(new Random(1))));
    }

    @Test
    void twoBuyersCanNeverShareTheLastUnit() {
        final MarketState state = fresh();
        state.openRotation("rot-1", 1_000, 100_000, selection(1));
        assertTrue(state.reserve("rot-1", "gen-core", MarketTestSupport.BUYER, 1, 2_000));
        // the second buyer hits the SAME check-and-decrement — refused
        assertFalse(state.reserve("rot-1", "gen-core", MarketTestSupport.RIVAL, 1, 2_000));
        assertEquals(0, state.offer("gen-core").stockLeft());
    }

    @Test
    void perPlayerLimitHoldsEvenWithStockLeft() {
        final MarketState state = fresh();
        state.openRotation("rot-1", 1_000, 100_000, selection(5));
        assertTrue(state.reserve("rot-1", "gen-core", MarketTestSupport.BUYER, 1, 2_000));
        assertFalse(state.reserve("rot-1", "gen-core", MarketTestSupport.BUYER, 1, 2_000));
        assertEquals(4, state.offer("gen-core").stockLeft());
        assertEquals(1, state.purchased("gen-core", MarketTestSupport.BUYER));
        // the rival is unaffected by the buyer's limit
        assertTrue(state.reserve("rot-1", "gen-core", MarketTestSupport.RIVAL, 1, 2_000));
    }

    @Test
    void staleRotationAndClosedWindowsAreRefused() {
        final MarketState state = fresh();
        state.openRotation("rot-2", 1_000, 100_000, selection(5));
        assertFalse(state.reserve("rot-1", "gen-core", MarketTestSupport.BUYER, 1, 2_000),
                "a click from an old page must never buy from the new rotation");
        assertFalse(state.reserve("rot-2", "gen-core", MarketTestSupport.BUYER, 1, 100_001),
                "past closes-at nothing sells");
        assertFalse(state.reserve("rot-2", "missing", MarketTestSupport.BUYER, 1, 2_000));
    }

    @Test
    void rollbackRestoresStockAndCount() {
        final MarketState state = fresh();
        state.openRotation("rot-1", 1_000, 100_000, selection(2));
        assertTrue(state.reserve("rot-1", "gen-core", MarketTestSupport.BUYER, 1, 2_000));
        state.rollbackReservation("gen-core", MarketTestSupport.BUYER);
        assertEquals(2, state.offer("gen-core").stockLeft());
        assertEquals(0, state.purchased("gen-core", MarketTestSupport.BUYER));
        // and the player may buy again after the rollback
        assertTrue(state.reserve("rot-1", "gen-core", MarketTestSupport.BUYER, 1, 2_000));
    }

    @Test
    void restartPreservesStockLimitsAndRotation() {
        final MarketState state = fresh();
        state.openRotation("rot-9", 1_000, 100_000, selection(3));
        assertTrue(state.reserve("rot-9", "gen-core", MarketTestSupport.BUYER, 1, 2_000));
        final MarketCost price = state.offer("gen-core").price();

        // "restart": a new state over the same file
        final MarketState reloaded = fresh();
        assertEquals("rot-9", reloaded.rotationId());
        assertEquals(2, reloaded.offer("gen-core").stockLeft());
        assertEquals(3, reloaded.offer("gen-core").stockTotal());
        assertEquals(price, reloaded.offer("gen-core").price());
        assertEquals(1, reloaded.purchased("gen-core", MarketTestSupport.BUYER));
        // the same-window reopen is a no-op — never a duplicate rotation
        assertFalse(reloaded.openRotation("rot-9", 5_000, 100_000, selection(3)));
        assertEquals(2, reloaded.offer("gen-core").stockLeft(), "stock untouched by reopen");
    }

    @Test
    void closingRemembersTheOffersForRepeatAvoidance() {
        final MarketState state = fresh();
        state.openRotation("rot-1", 1_000, 100_000, selection(3));
        state.closeRotation();
        assertNull(state.rotationId());
        assertEquals(Set.of("gen-core"), state.previousOfferIds());
        assertTrue(state.activeOffers().isEmpty());
        final MarketState reloaded = fresh();
        assertEquals(Set.of("gen-core"), reloaded.previousOfferIds());
    }

    @Test
    void historyIsBoundedAndSurvivesRestart() {
        final MarketState state = fresh();
        for (int index = 0; index < 60; index++) {
            state.recordSale(new MarketState.Sale("txn-" + index, "rot-1", "gen-core",
                    MarketTestSupport.BUYER, new MarketCost(1_000 + index, 0, 0), index));
        }
        assertEquals(50, state.sales().size(), "bounded to the history limit");
        assertEquals("txn-59", state.sales().get(49).txnId());
        state.recordLot(new MarketState.LotResult("auc-1", "lot-a", MarketTestSupport.BUYER,
                500_000, 99));
        state.recordLot(new MarketState.LotResult("auc-1", "lot-b", null, 0, 100));
        state.lastAuctionSession("2026-09-27 19:30");
        state.suppressRotation("rot-7");

        final MarketState reloaded = fresh();
        assertEquals(50, reloaded.sales().size());
        assertEquals("txn-10", reloaded.sales().get(0).txnId());
        assertEquals(2, reloaded.lotHistory().size());
        assertEquals(MarketTestSupport.BUYER, reloaded.lotHistory().get(0).winner());
        assertNull(reloaded.lotHistory().get(1).winner(), "cancelled lots keep a null winner");
        assertEquals("2026-09-27 19:30", reloaded.lastAuctionSession());
        assertEquals("rot-7", reloaded.suppressedRotation());
        assertEquals(Set.of("lot-b", "lot-a"), reloaded.recentLotIds(5));
    }

    @Test
    void corruptFilesStartFreshInsteadOfCrashing() throws Exception {
        final Path file = dir.resolve("black-market-data.yml");
        Files.writeString(file, "\t\tnot: yaml: at: all");
        final MarketState state = new MarketState(file, 50, MarketTestSupport.LOGGER);
        state.load();
        assertNull(state.rotationId());
        assertTrue(state.sales().isEmpty());
    }
}
