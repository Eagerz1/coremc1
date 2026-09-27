package com.coremc.core.quest;

import com.coremc.core.island.Island;
import org.bukkit.entity.Player;

/** Narrow adapter for a reward provider (Credits, keys, generators, etc.). */
public interface QuestRewardIntegration {

    enum Result {
        DELIVERED,
        UNAVAILABLE,
        FAILED
    }

    String type();

    default boolean available() {
        return true;
    }

    Result deliver(Player player, Island island, QuestConfig.RewardDef reward);

    static QuestRewardIntegration unavailable(final String type) {
        return new QuestRewardIntegration() {
            @Override
            public String type() {
                return QuestConfig.normalise(type);
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Result deliver(final Player player, final Island island,
                                  final QuestConfig.RewardDef reward) {
                return Result.UNAVAILABLE;
            }
        };
    }
}
