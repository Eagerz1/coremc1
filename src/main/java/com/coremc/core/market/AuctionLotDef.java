package com.coremc.core.market;

import com.coremc.core.reward.RewardDef;
import java.util.List;

/**
 * One configured Dark Auction lot: what is auctioned (the store's
 * reward model — cosmetics, progression materials, collectibles,
 * never exclusive combat power), its rarity, the starting Money bid
 * and the minimum raise. Sessions pick a handful of these
 * sequentially.
 */
public record AuctionLotDef(
        String id,
        RewardDef reward,
        List<String> description,
        MarketRarity rarity,
        long startingBid,
        long minIncrement) {

    public AuctionLotDef {
        description = description == null ? List.of() : List.copyOf(description);
        if (startingBid <= 0) {
            throw new IllegalArgumentException("lot '" + id + "': starting bid must be positive");
        }
        if (minIncrement <= 0) {
            throw new IllegalArgumentException("lot '" + id + "': min increment must be positive");
        }
    }
}
