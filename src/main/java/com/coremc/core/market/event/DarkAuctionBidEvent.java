package com.coremc.core.market.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a VALID Dark Auction bid lands (meaningful
 * participation — already validated against increment and balance).
 * Quest/Season objectives that count auction participation listen
 * here; invalid bids never fire it.
 */
public final class DarkAuctionBidEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID bidder;
    private final String sessionId;
    private final String lotId;
    private final long amount;

    public DarkAuctionBidEvent(final UUID bidder, final String sessionId, final String lotId,
                               final long amount) {
        this.bidder = bidder;
        this.sessionId = sessionId;
        this.lotId = lotId;
        this.amount = amount;
    }

    public UUID bidder() {
        return bidder;
    }

    public String sessionId() {
        return sessionId;
    }

    public String lotId() {
        return lotId;
    }

    public long amount() {
        return amount;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
