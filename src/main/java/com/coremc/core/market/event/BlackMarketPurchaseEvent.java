package com.coremc.core.market.event;

import com.coremc.core.market.MarketCost;
import com.coremc.core.market.MarketRarity;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired AFTER a limited-stock Black Market purchase has settled
 * (paid, delivered or safely pending). The authoritative hook for
 * Quests, Season Journey, Collections and Achievements — e.g.
 * "First Black Market purchase".
 *
 * <p>{@link #countsAsDiscovery()} is true ONLY when the offer's
 * config explicitly allows ownership discovery — buying never
 * silently completes a hidden gameplay discovery otherwise.</p>
 */
public final class BlackMarketPurchaseEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID buyer;
    private final String rotationId;
    private final String offerId;
    private final String rewardId;
    private final MarketRarity rarity;
    private final MarketCost price;
    private final boolean discovery;

    public BlackMarketPurchaseEvent(final UUID buyer, final String rotationId,
                                    final String offerId, final String rewardId,
                                    final MarketRarity rarity, final MarketCost price,
                                    final boolean discovery) {
        this.buyer = buyer;
        this.rotationId = rotationId;
        this.offerId = offerId;
        this.rewardId = rewardId;
        this.rarity = rarity;
        this.price = price;
        this.discovery = discovery;
    }

    public UUID buyer() {
        return buyer;
    }

    public String rotationId() {
        return rotationId;
    }

    public String offerId() {
        return offerId;
    }

    /** The delivered reward's stable id (PDC id for item rewards). */
    public String rewardId() {
        return rewardId;
    }

    public MarketRarity rarity() {
        return rarity;
    }

    public MarketCost price() {
        return price;
    }

    /** True only when config explicitly allows ownership discovery. */
    public boolean countsAsDiscovery() {
        return discovery;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
