package com.coremc.core.progress.reward;

import com.coremc.core.config.MessageService;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Delivers rewards that were held safely, the next time the player
 * logs in.
 *
 * <p>Runs a tick or two after join so the inventory is real, and only
 * says anything when something was actually handed over.</p>
 */
public final class PendingRewardListener implements Listener {

    private static final long DELAY_TICKS = 40L;

    private final JavaPlugin plugin;
    private final RewardService rewards;
    private final MessageService messages;

    public PendingRewardListener(final JavaPlugin plugin, final RewardService rewards,
                                 final MessageService messages) {
        this.plugin = plugin;
        this.rewards = rewards;
        this.messages = messages;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (rewards == null || rewards.pending().count(player.getUniqueId()) == 0) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            final int delivered = rewards.deliverPending(player);
            if (delivered > 0 && messages != null) {
                messages.sendPrefixed(player, "collections.pending-collected",
                        Map.of("amount", String.valueOf(delivered)));
            } else if (messages != null) {
                messages.sendPrefixed(player, "collections.pending-waiting",
                        Map.of("amount", String.valueOf(
                                rewards.pending().count(player.getUniqueId()))));
            }
        }, DELAY_TICKS);
    }
}
