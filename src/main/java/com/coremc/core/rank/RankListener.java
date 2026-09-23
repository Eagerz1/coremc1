package com.coremc.core.rank;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Rank join housekeeping: a rank removed while the player was offline
 * must not leave flight enabled, and any owed season payout is paid
 * the moment they join.
 */
public final class RankListener implements Listener {

    private final RankService ranks;

    public RankListener(final RankService ranks) {
        this.ranks = ranks;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        ranks.onJoin(event.getPlayer());
    }
}
