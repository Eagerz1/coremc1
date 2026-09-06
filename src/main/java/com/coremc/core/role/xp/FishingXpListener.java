package com.coremc.core.role.xp;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.role.RoleCategory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerFishEvent;

/** Fishing XP: catching fish/loot only. */
public final class FishingXpListener extends RoleXpListener {

    public FishingXpListener(final CoreMCPlugin plugin) {
        super(plugin, RoleCategory.FISHING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            award(event.getPlayer(), 5L);
        }
    }
}
