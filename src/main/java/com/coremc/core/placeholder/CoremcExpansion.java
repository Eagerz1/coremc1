package com.coremc.core.placeholder;

import com.coremc.core.essence.EssenceManager;
import com.coremc.core.essence.EssenceType;
import com.coremc.core.progress.ProfileSummaryService;
import com.coremc.core.shop.EconomyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * CoreMC's PlaceholderAPI expansion ({@code %coremc_...%}):
 *
 * <ul>
 *   <li>{@code %coremc_slayer_essence%} / {@code %coremc_mining_essence%}
 *       / {@code %coremc_farming_essence%} — comma-formatted balances</li>
 *   <li>{@code %coremc_essence_total%} — all three summed</li>
 *   <li>{@code %coremc_mob_kills%} — lifetime kill count</li>
 *   <li>{@code %coremc_coins%} — coin balance</li>
 *   <li>{@code %coremc_collection_percent%} — permanent Collection completion</li>
 *   <li>{@code %coremc_collections_complete%} — Collections finished</li>
 *   <li>{@code %coremc_achievement_points%} — prestige points (never spendable)</li>
 *   <li>{@code %coremc_achievements_earned%} — Achievements earned</li>
 *   <li>{@code %coremc_rewards_waiting%} — rewards waiting to be claimed</li>
 * </ul>
 *
 * Registered by CoreMC only when PlaceholderAPI is installed; persist() is
 * true so a PAPI reload never drops the plugin-provided expansion.
 */
public final class CoremcExpansion extends PlaceholderExpansion {

    private static final String VERSION = "1.0.0";

    private final EssenceManager essences;
    private final EconomyService economy;
    private final ProfileSummaryService profiles;

    public CoremcExpansion(final EssenceManager essences, final EconomyService economy) {
        this(essences, economy, null);
    }

    public CoremcExpansion(final EssenceManager essences, final EconomyService economy,
                           final ProfileSummaryService profiles) {
        this.essences = essences;
        this.economy = economy;
        this.profiles = profiles;
    }

    @Override
    public String getIdentifier() {
        return "coremc";
    }

    @Override
    public String getAuthor() {
        return "CoreMC";
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(final OfflinePlayer player, final String params) {
        if (params == null) {
            return null;
        }
        if (player == null) {
            // server-wide context: no profile to read
            return "0";
        }
        switch (params.toLowerCase()) {
            case "slayer_essence":
                return EssenceManager.format(essences.balance(player.getUniqueId(), EssenceType.SLAYER));
            case "mining_essence":
                return EssenceManager.format(essences.balance(player.getUniqueId(), EssenceType.MINING));
            case "farming_essence":
                return EssenceManager.format(essences.balance(player.getUniqueId(), EssenceType.FARMING));
            case "essence_total":
                return EssenceManager.format(essences.total(player.getUniqueId()));
            case "mob_kills":
                return EssenceManager.format(essences.kills(player.getUniqueId()));
            case "coins":
                return economy == null
                        ? EssenceManager.format(0)
                        : String.format("%,.0f", economy.balance(player.getUniqueId()));
            case "collection_percent":
                return String.valueOf(summary(player).collectionPercent());
            case "collections_complete":
                return String.valueOf(summary(player).collectionsComplete());
            case "collections_total":
                return String.valueOf(summary(player).collectionsTotal());
            case "achievement_points":
                return String.valueOf(summary(player).achievementPoints());
            case "achievements_earned":
                return String.valueOf(summary(player).achievementsEarned());
            case "achievements_total":
                return String.valueOf(summary(player).achievementsTotal());
            case "rewards_waiting":
                return String.valueOf(summary(player).claimable());
            case "rewards_held":
                return String.valueOf(summary(player).held());
            default:
                return null;
        }
    }

    /** Never null: an absent progression system simply reports zeroes. */
    private ProfileSummaryService.Summary summary(final OfflinePlayer player) {
        if (profiles == null) {
            return new ProfileSummaryService.Summary(0, 0, 0, 0, 0, 0, 0, 0);
        }
        return profiles.of(player.getUniqueId());
    }
}
