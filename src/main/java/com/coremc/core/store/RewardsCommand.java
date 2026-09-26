package com.coremc.core.store;

import com.coremc.core.config.MessageService;
import com.coremc.core.reward.PendingRewards;
import com.coremc.core.reward.RewardDeliverer;
import java.util.Map;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /rewards} — safe delivery for everything owed: purchases that
 * did not fit the inventory, lootbox winnings from interrupted
 * openings, rewards granted while offline. Delivery is idempotent —
 * each grant leaves the ledger only when it is really in the player's
 * hands.
 */
public final class RewardsCommand implements CommandExecutor {

    private final PendingRewards pending;
    private final RewardDeliverer deliverer;
    private final MessageService messages;

    public RewardsCommand(final PendingRewards pending, final RewardDeliverer deliverer,
                          final MessageService messages) {
        this.pending = pending;
        this.deliverer = deliverer;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "store.only-players");
            return true;
        }
        if (pending == null || deliverer == null) {
            messages.sendPrefixed(sender, "store.unavailable");
            return true;
        }
        final int owed = pending.grantCount(player.getUniqueId());
        if (owed == 0) {
            messages.sendPrefixed(player, "store.rewards-none");
            return true;
        }
        final int delivered = pending.deliver(player.getUniqueId(),
                grant -> deliverer.deliver(player, grant));
        final int remaining = pending.grantCount(player.getUniqueId());
        if (delivered > 0) {
            messages.sendPrefixed(player, "store.rewards-delivered", Map.of(
                    "count", String.valueOf(delivered)));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
        }
        if (remaining > 0) {
            messages.sendPrefixed(player, "store.rewards-remaining", Map.of(
                    "count", String.valueOf(remaining)));
            if (delivered == 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
            }
        }
        return true;
    }
}
