package com.coremc.core.market;

import com.coremc.core.market.event.BlackMarketPurchaseEvent;
import com.coremc.core.market.event.DarkAuctionBidEvent;
import com.coremc.core.market.event.DarkAuctionWinEvent;
import java.util.UUID;
import org.bukkit.Bukkit;

/** Publishes the market's Bukkit events (runtime implementation). */
public final class BukkitMarketEvents implements MarketEvents {

    @Override
    public void purchase(final UUID buyer, final String rotationId, final MarketOffer offer,
                         final MarketCost price) {
        Bukkit.getPluginManager().callEvent(new BlackMarketPurchaseEvent(buyer, rotationId,
                offer.id(), offer.reward().id(), offer.pool(), price, offer.discovery()));
    }

    @Override
    public void bid(final UUID bidder, final String sessionId, final String lotId,
                    final long amount) {
        Bukkit.getPluginManager().callEvent(new DarkAuctionBidEvent(bidder, sessionId, lotId,
                amount));
    }

    @Override
    public void win(final UUID winner, final String sessionId, final AuctionLotDef lot,
                    final long amount) {
        Bukkit.getPluginManager().callEvent(new DarkAuctionWinEvent(winner, sessionId, lot.id(),
                lot.rarity(), amount));
    }
}
