package com.coremc.core.season;

import com.coremc.core.island.Island;
import org.bukkit.entity.Player;

/** Adapter seam for Season Journey reward delivery. Missing later-branch systems return PENDING. */
public interface SeasonRewardIntegration {
    enum Result { DELIVERED, PENDING }

    Result deliver(Player player, Island island, SeasonConfig.Reward reward);
}
