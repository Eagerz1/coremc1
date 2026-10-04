package com.coremc.core.quest;

import org.bukkit.Material;

/** One daily objective and its non-store reward. */
public record QuestDefinition(
        String id,
        String display,
        Material icon,
        Metric metric,
        long target,
        long rewardCredits,
        long rewardTokens) {

    public enum Metric {
        MINE_BLOCK,
        CHOP_LOG,
        HARVEST_CROP,
        CATCH_FISH,
        KILL_MOB
    }
}
