package com.coremc.core.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * One Dark Auction lot's bid state machine — pure and clock-injected
 * so every rule is unit-testable:
 *
 * <ul>
 *   <li>no fund reservation: bids only VALIDATE affordability (the
 *       existing EconomyService cannot escrow), so nobody is charged
 *       for merely bidding,</li>
 *   <li>anti-snipe: a valid bid inside the threshold extends the
 *       timer, up to a configurable total cap,</li>
 *   <li>settlement runs EXACTLY once: it walks the recorded valid
 *       bids from the highest down and charges the first bidder who
 *       can still pay — the winner pays their own bid, non-winners
 *       pay nothing, and when nobody can pay the lot cancels and
 *       debits nobody.</li>
 * </ul>
 */
public final class AuctionEngine {

    /** Why a bid was accepted or refused. */
    public enum BidResult {
        OK, ENDED, TOO_LOW, CANNOT_AFFORD
    }

    /** One recorded valid bid. */
    public record Bid(UUID bidder, long amount, long at) {
    }

    /** The exactly-once settlement outcome. */
    public record Settlement(UUID winner, long amount) {

        public boolean sold() {
            return winner != null;
        }
    }

    private final AuctionLotDef lot;
    private final long antiSnipeThresholdMillis;
    private final long antiSnipeExtensionMillis;
    private final long antiSnipeCapMillis;

    private final List<Bid> bids = new ArrayList<>();
    private long endsAt;
    private long extendedTotal;
    private Settlement settlement;

    public AuctionEngine(final AuctionLotDef lot, final long startAt, final long durationMillis,
                         final long antiSnipeThresholdMillis, final long antiSnipeExtensionMillis,
                         final long antiSnipeCapMillis) {
        this.lot = lot;
        this.endsAt = startAt + durationMillis;
        this.antiSnipeThresholdMillis = antiSnipeThresholdMillis;
        this.antiSnipeExtensionMillis = antiSnipeExtensionMillis;
        this.antiSnipeCapMillis = antiSnipeCapMillis;
    }

    public AuctionLotDef lot() {
        return lot;
    }

    /** The current highest bid, or 0 before the first bid. */
    public long currentBid() {
        return bids.isEmpty() ? 0 : bids.get(bids.size() - 1).amount();
    }

    /** The current highest bidder, or null before the first bid. */
    public UUID highestBidder() {
        return bids.isEmpty() ? null : bids.get(bids.size() - 1).bidder();
    }

    /** The smallest amount the next valid bid must reach. */
    public long minNextBid() {
        return bids.isEmpty() ? lot.startingBid() : currentBid() + lot.minIncrement();
    }

    public long remainingMillis(final long now) {
        return Math.max(0, endsAt - now);
    }

    public boolean ended(final long now) {
        return now >= endsAt;
    }

    /** Total anti-snipe time added so far. */
    public long extendedTotalMillis() {
        return extendedTotal;
    }

    /** All recorded valid bids, oldest first. */
    public List<Bid> bids() {
        return Collections.unmodifiableList(bids);
    }

    /**
     * Validates and records a bid. {@code canAfford} is consulted on
     * EVERY bid (no reservation, so affordability is re-checked at
     * settlement too). A valid bid inside the anti-snipe threshold
     * extends the timer up to the cap.
     */
    public BidResult placeBid(final UUID bidder, final long amount, final long now,
                              final BiPredicate<UUID, Long> canAfford) {
        if (settlement != null || ended(now)) {
            return BidResult.ENDED;
        }
        if (amount < minNextBid()) {
            return BidResult.TOO_LOW;
        }
        if (!canAfford.test(bidder, amount)) {
            return BidResult.CANNOT_AFFORD;
        }
        bids.add(new Bid(bidder, amount, now));
        if (endsAt - now < antiSnipeThresholdMillis && extendedTotal < antiSnipeCapMillis) {
            final long extension = Math.min(antiSnipeExtensionMillis,
                    antiSnipeCapMillis - extendedTotal);
            endsAt += extension;
            extendedTotal += extension;
        }
        return BidResult.OK;
    }

    /** True once {@link #settle} has run. */
    public boolean settled() {
        return settlement != null;
    }

    /**
     * Settles EXACTLY once: charges the highest bidder who can still
     * pay (walking stored valid bids from the top), or cancels with
     * nobody charged. Calling again returns the same outcome without
     * charging anyone twice.
     *
     * @param charge attempts the actual debit; must be atomic and
     *               refuse without partial effects (EconomyService
     *               withdraw semantics)
     */
    public Settlement settle(final BiPredicate<UUID, Long> charge) {
        if (settlement != null) {
            return settlement;
        }
        for (int index = bids.size() - 1; index >= 0; index--) {
            final Bid bid = bids.get(index);
            if (charge.test(bid.bidder(), bid.amount())) {
                settlement = new Settlement(bid.bidder(), bid.amount());
                return settlement;
            }
        }
        settlement = new Settlement(null, 0);
        return settlement;
    }
}
