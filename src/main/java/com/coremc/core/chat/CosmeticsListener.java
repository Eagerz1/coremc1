package com.coremc.core.chat;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps cosmetic state healthy across sessions:
 *  - refreshes the cached rank prefix on join (MAIN thread) so the async
 *    chat renderer never has to touch permissions off-thread,
 *  - runs the idempotent cosmetic migration on the loaded profile,
 *  - drops the cached prefix on quit.
 *
 * Registered at MONITOR/ignoreCancelled to stay out of the way of other
 * join handlers (it changes nothing about the event itself).
 */
public final class CosmeticsListener implements Listener {

    private final CoreMCPlugin plugin;

    public CosmeticsListener(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        plugin.ranks().refresh(player);
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        if (CosmeticMigration.migrate(profile, plugin.tags().catalog(), plugin.chatStyles().catalog())) {
            plugin.playerData().persistImportant(profile);
            plugin.getLogger().fine("Migrated cosmetic ids for " + player.getName() + ".");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        plugin.ranks().forget(event.getPlayer().getUniqueId());
    }
}
