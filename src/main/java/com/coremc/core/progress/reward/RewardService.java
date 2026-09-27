package com.coremc.core.progress.reward;

import com.coremc.core.progress.adapter.ExternalSystems;
import com.coremc.core.shop.EconomyService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Hands over the claimable half of Collection and Achievement rewards:
 * items, coins, and the currencies that live behind adapters (Sky
 * Tokens, Credits, keys, Journey XP).
 *
 * <p>Two hard rules:</p>
 * <ul>
 *   <li><b>Never drop anything valuable.</b> A full inventory, an
 *       offline player or a currency whose system is on another branch
 *       all end the same way: the reward is parked in
 *       {@link PendingRewardStore} and delivered later.</li>
 *   <li><b>Exactly once.</b> Callers record the claim before calling;
 *       pending records are removed by unique id as they are
 *       delivered, so a reward can never be handed over twice.</li>
 * </ul>
 *
 * <p>Permanent unlocks (recipes, cosmetics, titles, access flags) never
 * come through here — they are applied and remembered by the
 * Collection/Achievement profiles themselves.</p>
 */
public final class RewardService {

    private final EconomyService economy;
    private final ExternalSystems externals;
    private final PendingRewardStore pendingStore;
    private final Logger logger;

    public RewardService(final EconomyService economy, final ExternalSystems externals,
                         final PendingRewardStore pendingStore, final Logger logger) {
        this.economy = economy;
        this.externals = externals;
        this.pendingStore = pendingStore;
        this.logger = logger;
    }

    /** The pending store (used by the GUIs to show "waiting for you"). */
    public PendingRewardStore pending() {
        return pendingStore;
    }

    /**
     * Delivers one reward now, or parks it safely.
     *
     * @param player the receiving player's id (may be offline)
     * @param reward what to hand over
     * @param source short label recorded with a parked reward
     */
    public RewardGrant deliver(final UUID player, final Reward reward, final String source) {
        if (player == null || reward == null) {
            return RewardGrant.FAILED;
        }
        if (reward.type().permanent()) {
            // permanent unlocks are owned by the calling profile
            return RewardGrant.UNLOCKED;
        }
        return switch (reward.type()) {
            case COINS -> deliverCoins(player, reward, source);
            case ITEM -> deliverItem(player, reward, source);
            case SKY_TOKENS -> adapterOrPending(player, reward, source,
                    externals.rewards().grantSkyTokens(player, reward.amount()));
            case CREDITS -> adapterOrPending(player, reward, source,
                    externals.rewards().grantCredits(player, reward.amount()));
            case KEY -> adapterOrPending(player, reward, source,
                    externals.rewards().grantKey(player, reward.id(), (int) reward.amount()));
            case JOURNEY_XP -> adapterOrPending(player, reward, source,
                    externals.seasonJourney().grantJourneyXp(player, reward.amount()));
            default -> RewardGrant.FAILED;
        };
    }

    /**
     * Tries to deliver everything waiting for an online player (called
     * on join and after a claim). Returns how many were handed over.
     */
    public int deliverPending(final Player player) {
        if (player == null) {
            return 0;
        }
        final UUID id = player.getUniqueId();
        int delivered = 0;
        final List<PendingReward> waiting = pendingStore.of(id);
        for (final PendingReward record : waiting) {
            final RewardGrant result = deliverDirect(player, record.reward());
            if (result == RewardGrant.GRANTED) {
                pendingStore.remove(id, record.id());
                delivered++;
            }
        }
        return delivered;
    }

    /** Attempts a straight delivery without ever re-parking the reward. */
    private RewardGrant deliverDirect(final Player player, final Reward reward) {
        final UUID id = player.getUniqueId();
        return switch (reward.type()) {
            case COINS -> {
                if (economy == null) {
                    yield RewardGrant.PENDING;
                }
                economy.deposit(id, reward.amount());
                yield RewardGrant.GRANTED;
            }
            case ITEM -> giveItem(player, reward) ? RewardGrant.GRANTED : RewardGrant.PENDING;
            case SKY_TOKENS -> externals.rewards().grantSkyTokens(id, reward.amount())
                    ? RewardGrant.GRANTED : RewardGrant.PENDING;
            case CREDITS -> externals.rewards().grantCredits(id, reward.amount())
                    ? RewardGrant.GRANTED : RewardGrant.PENDING;
            case KEY -> externals.rewards().grantKey(id, reward.id(), (int) reward.amount())
                    ? RewardGrant.GRANTED : RewardGrant.PENDING;
            case JOURNEY_XP -> externals.seasonJourney().grantJourneyXp(id, reward.amount())
                    ? RewardGrant.GRANTED : RewardGrant.PENDING;
            default -> RewardGrant.FAILED;
        };
    }

    private RewardGrant deliverCoins(final UUID player, final Reward reward, final String source) {
        if (economy == null) {
            return park(player, reward, source);
        }
        economy.deposit(player, reward.amount());
        return RewardGrant.GRANTED;
    }

    private RewardGrant deliverItem(final UUID player, final Reward reward, final String source) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null || !giveItem(online, reward)) {
            return park(player, reward, source);
        }
        return RewardGrant.GRANTED;
    }

    private RewardGrant adapterOrPending(final UUID player, final Reward reward,
                                         final String source, final boolean granted) {
        return granted ? RewardGrant.GRANTED : park(player, reward, source);
    }

    private RewardGrant park(final UUID player, final Reward reward, final String source) {
        pendingStore.add(player, reward, source);
        return RewardGrant.PENDING;
    }

    /** True when the whole stack fitted in the player's inventory. */
    private boolean giveItem(final Player player, final Reward reward) {
        final Material material = material(reward.id());
        if (material == null) {
            if (logger != null) {
                logger.warning("Reward item '" + reward.id() + "' is not a material — parked.");
            }
            return false;
        }
        final int amount = (int) Math.max(1, Math.min(2304, reward.amount()));
        final var leftover = player.getInventory().addItem(new ItemStack(material, amount));
        return leftover.isEmpty();
    }

    private static Material material(final String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Material.valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            return null;
        }
    }
}
