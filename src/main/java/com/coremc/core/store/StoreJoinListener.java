package com.coremc.core.store;

import com.coremc.core.config.MessageService;
import com.coremc.core.crate.CrateConfig;
import com.coremc.core.rank.RankService;
import com.coremc.core.reward.PendingRewards;
import com.coremc.core.reward.RewardDeliverer;
import com.coremc.core.reward.RewardGrant;
import com.coremc.core.reward.RewardType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Join-time store housekeeping:
 *
 * <ul>
 *   <li>delivers pending rewards (interrupted lootbox openings,
 *       full-inventory purchases, offline wins) a moment after the
 *       player appears — idempotent, nothing can double-deliver,</li>
 *   <li>migrates the rank ladder's virtual River key counters into
 *       real PDC River Keys through the same pending ledger (the
 *       pre-crate behaviour is preserved: ranks still grant the keys,
 *       they just become physical now).</li>
 * </ul>
 */
public final class StoreJoinListener implements Listener {

    private static final String RIVER_KEY_ID = "river";

    private final JavaPlugin plugin;
    private final PendingRewards pending;
    private final RewardDeliverer deliverer;
    private final RankService ranks;
    private final CrateConfig crates;
    private final TransactionLog transactions;
    private final MessageService messages;

    public StoreJoinListener(final JavaPlugin plugin, final PendingRewards pending,
                             final RewardDeliverer deliverer, final RankService ranks,
                             final CrateConfig crates, final TransactionLog transactions,
                             final MessageService messages) {
        this.plugin = plugin;
        this.pending = pending;
        this.deliverer = deliverer;
        this.ranks = ranks;
        this.crates = crates;
        this.transactions = transactions;
        this.messages = messages;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            migrateRiverKeys(player);
            deliverPending(player);
        }, 40L);
    }

    /** Rank-granted virtual River keys become physical PDC keys, once. */
    private void migrateRiverKeys(final Player player) {
        if (ranks == null || crates == null || !crates.enabled()
                || crates.key(RIVER_KEY_ID) == null) {
            return;
        }
        final int drained = ranks.drainRiverKeys(player.getUniqueId());
        if (drained <= 0) {
            return;
        }
        final String txnId = "riverkey-migration-" + UUID.randomUUID();
        pending.add(player.getUniqueId(), txnId, "rank:river-keys", List.of(
                new RewardGrant(RewardType.KEY, RIVER_KEY_ID, drained,
                        crates.key(RIVER_KEY_ID).name())));
        transactions.record(txnId, player.getUniqueId(), "RANK_RIVER_KEYS",
                "migrated=" + drained);
        messages.sendPrefixed(player, "store.river-keys-migrated", Map.of(
                "amount", String.valueOf(drained)));
    }

    private void deliverPending(final Player player) {
        final int owed = pending.grantCount(player.getUniqueId());
        if (owed == 0) {
            return;
        }
        final int delivered = pending.deliver(player.getUniqueId(),
                grant -> deliverer.deliver(player, grant));
        if (delivered > 0) {
            messages.sendPrefixed(player, "store.rewards-delivered", Map.of(
                    "count", String.valueOf(delivered)));
        }
        final int remaining = pending.grantCount(player.getUniqueId());
        if (remaining > 0) {
            messages.sendPrefixed(player, "store.rewards-remaining", Map.of(
                    "count", String.valueOf(remaining)));
        }
    }
}
