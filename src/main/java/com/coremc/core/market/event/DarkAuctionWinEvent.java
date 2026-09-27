package com.coremc.core.market.event;

import com.coremc.core.market.MarketRarity;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired AFTER a Dark Auction lot settled exactly once: the winner
 * paid and the reward is delivered (or safely pending). Achievements
 * like "First Dark Auction win" / "wins across different rarities"
 * hook here. Cancelled lots never fire it.
 */
public final class DarkAuctionWinEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID winner;
    private final String sessionId;
    private final String lotId;
    private final MarketRarity rarity;
    private final long amount;

    public DarkAuctionWinEvent(final UUID winner, final String sessionId, final String lotId,
                               final MarketRarity rarity, final long amount) {
        this.winner = winner;
        this.sessionId = sessionId;
        this.lotId = lotId;
        this.rarity = rarity;
        this.amount = amount;
    }

    public UUID winner() {
        return winner;
    }

    public String sessionId() {
        return sessionId;
    }

    public String lotId() {
        return lotId;
    }

    public MarketRarity rarity() {
        return rarity;
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
