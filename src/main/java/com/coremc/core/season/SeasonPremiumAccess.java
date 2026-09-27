package com.coremc.core.season;

import org.bukkit.entity.Player;

/** Ownership seam for optional premium Season Journey access. No payment logic lives here. */
public interface SeasonPremiumAccess {
    boolean hasPremium(Player player);

    static SeasonPremiumAccess none() {
        return player -> false;
    }
}
