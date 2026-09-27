package com.coremc.core.collections;

import com.coremc.core.progress.reward.Reward;
import java.util.List;

/**
 * A milestone the player has just reached: what was unlocked
 * automatically, and what is now waiting to be claimed by hand.
 *
 * @param entry     the Collection entry
 * @param milestone the tier that was reached
 * @param total     the player's total after the event
 * @param automatic permanent unlocks applied there and then
 * @param claimable rewards the player must claim in the GUI
 */
public record MilestoneAward(CollectionEntry entry, CollectionMilestone milestone, long total,
                             List<Reward> automatic, List<Reward> claimable) {

    public MilestoneAward {
        automatic = automatic == null ? List.of() : List.copyOf(automatic);
        claimable = claimable == null ? List.of() : List.copyOf(claimable);
    }

    /** True when the milestone completed the whole entry. */
    public boolean completesEntry() {
        return milestone.index() == entry.tiers() - 1;
    }

    /** True when something is waiting in the GUI. */
    public boolean hasClaimable() {
        return !claimable.isEmpty();
    }
}
