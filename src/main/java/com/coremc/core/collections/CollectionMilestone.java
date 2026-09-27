package com.coremc.core.collections;

import com.coremc.core.progress.reward.Reward;
import java.util.List;

/**
 * One milestone tier of a Collection entry: an amount to reach and
 * what reaching it gives.
 *
 * <p>Thresholds are per entry on purpose — a Cobblestone Collection
 * can climb 250 / 1,000 / 5,000 / 20,000 / 75,000 while a Champion
 * Collection climbs 1 / 5 / 25. Nothing forces a shared curve.</p>
 *
 * @param index    0-based tier index inside its entry
 * @param amount   the total needed to reach this tier
 * @param rewards  what the tier hands over (may be empty)
 */
public record CollectionMilestone(int index, long amount, List<Reward> rewards) {

    public CollectionMilestone {
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
    }

    /** Human tier number (1-based). */
    public int tier() {
        return index + 1;
    }

    /** True when at least one reward must be claimed by hand. */
    public boolean hasManualReward() {
        return rewards.stream().anyMatch(Reward::manual);
    }

    /** True when the tier only applies permanent unlocks. */
    public boolean automaticOnly() {
        return !rewards.isEmpty() && rewards.stream().noneMatch(Reward::manual);
    }
}
