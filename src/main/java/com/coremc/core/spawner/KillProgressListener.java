package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Feeds spawner progression from every entity death with a player
 * killer. Two distinct routes, both gated by the same rolling kill cap:
 *
 *  - <b>wild mobs</b> increment the killer's per-entity unlock counter
 *    (the 25-zombie → spawner I journey),
 *  - <b>spawner-born mobs</b> never count toward wild unlocks (bought
 *    spawners cannot trivialise the unlock grind); they pay the
 *    configured Core money / Sky Token / island-XP reward instead, and
 *    Ancient mobs grant triple progress toward the NEXT mob lane as a
 *    single kill event.
 */
public final class KillProgressListener implements Listener {

    private final CoreMCPlugin plugin;

    public KillProgressListener(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(final EntityDeathEvent event) {
        final LivingEntity victim = event.getEntity();
        if (victim instanceof Player) {
            return;
        }
        final Player killer = victim.getKiller();
        if (killer == null) {
            return;
        }
        final PlayerProfile profile =
                plugin.playerData().profileOf(killer.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        if (plugin.spawners().tags().isSpawnerBorn(victim)) {
            plugin.spawners().recordSpawnerKill(killer, profile, victim);
        } else {
            plugin.spawners().recordKill(killer, profile, victim.getType());
        }
    }
}
