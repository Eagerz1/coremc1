package com.coremc.core.player;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.config.MessageService;
import java.util.Map;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Feeds join/quit lifecycle events into {@link PlayerDataService} and
 * sends the branded first-join welcome.
 *
 * The pre-login event is asynchronous, so profile disk I/O happens off
 * the main thread by design.
 */
public final class PlayerListener implements Listener {

    private final PlayerDataService dataService;
    private final MessageService messages;
    private final CoreConfig config;

    public PlayerListener(final PlayerDataService dataService, final MessageService messages, final CoreConfig config) {
        this.dataService = dataService;
        this.messages = messages;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        dataService.handlePreLogin(event.getUniqueId(), event.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final Optional<PlayerProfile> profile = dataService.handleJoin(player.getUniqueId(), player.getName());
        if (profile.isEmpty()) {
            return;
        }
        if (config.firstJoinMessage() && profile.get().totalLogins() == 1L) {
            messages.sendPrefixed(player, "welcome-first-join", Map.of("player", player.getName()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        dataService.handleQuit(event.getPlayer().getUniqueId());
    }
}
