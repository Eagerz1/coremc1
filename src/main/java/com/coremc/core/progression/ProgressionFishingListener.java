package com.coremc.core.progression;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;

/** Island XP for active fishing. Only real caught-fish events count. */
public final class ProgressionFishingListener implements Listener {

    private final IslandProgressionService progression;

    public ProgressionFishingListener(final IslandProgressionService progression) {
        this.progression = progression;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        progression.recordActivity(event.getPlayer(), event.getPlayer().getLocation(), "fishing", 1L);
    }
}
