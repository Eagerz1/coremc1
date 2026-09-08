package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Feeds spawner unlock progression: every entity death with a player
 * killer increments that player's kill counter for the entity type.
 */
public final class KillProgressListener implements Listener {

    private final CoreMCPlugin plugin;

    public KillProgressListener(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(final EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        plugin.playerData().profileOf(killer.getUniqueId()).ifPresent(profile ->
                plugin.spawners().recordKill(killer, profile, event.getEntityType()));
    }
}
