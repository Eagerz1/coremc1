package com.coremc.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Dark Auction bid state machine: validation, highest-bidder
 * tracking, anti-snipe extensions with their cap, exactly-once
 * settlement, broke-winner fallback and safe cancellation — nobody
 * who does not win is ever charged.
 */
class AuctionEngineTest {

    private static final UUID ALICE = MarketTestSupport.BUYER;
    private static final UUID BOB = MarketTestSupport.RIVAL;
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    private static AuctionEngine engine() {
        // 60s lot, 10s snipe threshold, +10s extension, 30s cap
        return new AuctionEngine(MarketTestSupport.lot("gen-core", 250_000, 25_000),
                0, 60_000, 10_000, 10_000, 30_000);
    }

    @Test
    void bidValidationCoversEveryRefusal() {
        final AuctionEngine auction = engine();
        assertEquals(AuctionEngine.BidResult.TOO_LOW,
                auction.placeBid(ALICE, 249_999, 1_000, (who, amount) -> true),
                "below the starting bid");
        assertEquals(AuctionEngine.BidResult.CANNOT_AFFORD,
                auction.placeBid(ALICE, 250_000, 1_000, (who, amount) -> false),
                "bids must be backed by the live balance");
        assertEquals(AuctionEngine.BidResult.OK,
                auction.placeBid(ALICE, 250_000, 1_000, (who, amount) -> true));
        assertEquals(AuctionEngine.BidResult.TOO_LOW,
                auction.placeBid(BOB, 274_999, 2_000, (who, amount) -> true),
                "below current + min increment");
        assertEquals(AuctionEngine.BidResult.OK,
                auction.placeBid(BOB, 275_000, 2_000, (who, amount) -> true));
        assertEquals(AuctionEngine.BidResult.ENDED,
                auction.placeBid(ALICE, 500_000, 60_001, (who, amount) -> true),
                "no bids after the clock runs out");
    }

    @Test
    void highestBidderIsTracked() {
        final AuctionEngine auction = engine();
        assertNull(auction.highestBidder());
        assertEquals(250_000, auction.minNextBid());
        auction.placeBid(ALICE, 250_000, 1_000, (who, amount) -> true);
        assertEquals(ALICE, auction.highestBidder());
        assertEquals(275_000, auction.minNextBid());
        auction.placeBid(BOB, 300_000, 2_000, (who, amount) -> true);
        assertEquals(BOB, auction.highestBidder());
        assertEquals(300_000, auction.currentBid());
    }

    @Test
    void antiSnipeExtendsUpToTheCapOnly() {
        final AuctionEngine auction = engine();
        // a bid with 5s left (threshold 10s): +10s
        auction.placeBid(ALICE, 250_000, 55_000, (who, amount) -> true);
        assertEquals(15_000, auction.remainingMillis(55_000));
        assertEquals(10_000, auction.extendedTotalMillis());
        // two more snipes: +10s +10s reaches the 30s cap
        auction.placeBid(BOB, 275_000, 65_000, (who, amount) -> true);
        auction.placeBid(ALICE, 300_000, 75_000, (who, amount) -> true);
        assertEquals(30_000, auction.extendedTotalMillis());
        // a fourth snipe extends NOTHING — the cap is hard
        auction.placeBid(BOB, 325_000, 85_000, (who, amount) -> true);
        assertEquals(30_000, auction.extendedTotalMillis());
        assertTrue(auction.ended(90_000));
        // an early bid never extends
        final AuctionEngine calm = engine();
        calm.placeBid(ALICE, 250_000, 10_000, (who, amount) -> true);
        assertEquals(0, calm.extendedTotalMillis());
    }

    @Test
    void settlementChargesTheWinnerExactlyOnce() {
        final AuctionEngine auction = engine();
        auction.placeBid(ALICE, 250_000, 1_000, (who, amount) -> true);
        auction.placeBid(BOB, 300_000, 2_000, (who, amount) -> true);
        final List<String> charges = new ArrayList<>();
        final AuctionEngine.Settlement first = auction.settle((who, amount) -> {
            charges.add(who + ":" + amount);
            return true;
        });
        assertEquals(BOB, first.winner());
        assertEquals(300_000, first.amount());
        assertEquals(List.of(BOB + ":300000"), charges, "one charge, the winner only");
        // settling again is a no-op returning the same outcome
        final AuctionEngine.Settlement again = auction.settle((who, amount) -> {
            charges.add("EXTRA");
            return true;
        });
        assertEquals(first, again);
        assertEquals(1, charges.size(), "nobody is ever charged twice");
        assertTrue(auction.settled());
    }

    @Test
    void brokeWinnerFallsBackThroughStoredBids() {
        final AuctionEngine auction = engine();
        auction.placeBid(ALICE, 250_000, 1_000, (who, amount) -> true);
        auction.placeBid(BOB, 300_000, 2_000, (who, amount) -> true);
        auction.placeBid(CAROL, 350_000, 3_000, (who, amount) -> true);
        final List<String> attempts = new ArrayList<>();
        // Carol went broke since her bid; Bob can still pay
        final AuctionEngine.Settlement result = auction.settle((who, amount) -> {
            attempts.add(who + ":" + amount);
            return !who.equals(CAROL);
        });
        assertEquals(BOB, result.winner());
        assertEquals(300_000, result.amount(), "the fallback pays the FALLBACK bid");
        assertEquals(List.of(CAROL + ":350000", BOB + ":300000"), attempts);
    }

    @Test
    void whenNobodyCanPayTheLotCancelsAndDebitsNobody() {
        final AuctionEngine auction = engine();
        auction.placeBid(ALICE, 250_000, 1_000, (who, amount) -> true);
        final AuctionEngine.Settlement result = auction.settle((who, amount) -> false);
        assertFalse(result.sold());
        assertNull(result.winner());
        // a lot with zero bids cancels the same way
        final AuctionEngine empty = engine();
        assertFalse(empty.settle((who, amount) -> true).sold());
    }
}
