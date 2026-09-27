package com.coremc.core.market;

import com.coremc.core.reward.RewardDef;
import java.util.List;

/**
 * One configured Black Market offer: a stable id, the reward it
 * delivers (the store's own {@link RewardDef} model — PDC items,
 * keys, lootboxes, currencies, commands), which pool it rotates in,
 * its price band, global stock, per-player limit and any progression
 * requirements. Pure config data; the live rotation resolves price
 * and tracks stock.
 *
 * <p>{@code discovery} marks offers whose purchase may legitimately
 * count as item discovery/ownership for Collections — off by default
 * so buying never silently completes a hidden gameplay discovery.</p>
 */
public record MarketOffer(
        String id,
        RewardDef reward,
        List<String> description,
        MarketRarity pool,
        CostBand cost,
        int stock,
        int perPlayer,
        List<Requirement> requirements,
        boolean discovery) {

    /** One progression requirement: {@code type} + {@code value}. */
    public record Requirement(String type, String value) {
    }

    public MarketOffer {
        description = description == null ? List.of() : List.copyOf(description);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        if (stock <= 0) {
            throw new IllegalArgumentException("offer '" + id + "': stock must be positive");
        }
        if (perPlayer <= 0) {
            throw new IllegalArgumentException("offer '" + id + "': per-player limit must be positive");
        }
    }
}
