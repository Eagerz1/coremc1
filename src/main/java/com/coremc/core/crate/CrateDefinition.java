package com.coremc.core.crate;

import java.util.List;

/**
 * One openable crate: the key(s) it takes, the weighted reward pool,
 * and the pity guarantee (every Nth open without a jackpot pays the
 * pity reward instead of rolling). Identity + balance all come from
 * {@code crates.yml crates:}.
 */
public record CrateDefinition(
        String id,
        String display,
        String icon,
        /** Key ids that open this crate (first = primary display). */
        List<String> keys,
        /** Weighted rewards (chance = weight / pool total). */
        List<CrateReward> rewards,
        /** Opens without a jackpot before the pity reward pays (0 = no pity). */
        int pityCount,
        /** Guaranteed reward at the pity threshold (null = no pity). */
        CrateReward pityReward) {

    /** Sum of pool weights (chance denominator for previews). */
    public int totalWeight() {
        int total = 0;
        for (final CrateReward reward : rewards) {
            total += Math.max(0, reward.weight());
        }
        return total;
    }

    /** Profile-stats key tracking this crate's pity counter. */
    public String pityStatKey() {
        return "crate-pity:" + id;
    }
}
