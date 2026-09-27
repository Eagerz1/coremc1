package com.coremc.core.market;

import java.util.UUID;

/**
 * The market's outbound event port. The runtime implementation
 * publishes the Bukkit events in {@code com.coremc.core.market.event}
 * (the integration surface for Quests, Season Journey, Collections
 * and Achievements); unit tests plug in a recorder. Services never
 * call Bukkit directly for events, so every flow stays headless-
 * testable.
 */
public interface MarketEvents {

    void purchase(UUID buyer, String rotationId, MarketOffer offer, MarketCost price);

    void bid(UUID bidder, String sessionId, String lotId, long amount);

    void win(UUID winner, String sessionId, AuctionLotDef lot, long amount);

    /** A no-op sink for disabled setups and tests. */
    static MarketEvents none() {
        return new MarketEvents() {
            @Override
            public void purchase(final UUID buyer, final String rotationId,
                                 final MarketOffer offer, final MarketCost price) {
            }

            @Override
            public void bid(final UUID bidder, final String sessionId, final String lotId,
                            final long amount) {
            }

            @Override
            public void win(final UUID winner, final String sessionId, final AuctionLotDef lot,
                            final long amount) {
            }
        };
    }
}
