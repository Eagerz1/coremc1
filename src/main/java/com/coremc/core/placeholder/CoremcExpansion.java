package com.coremc.core.placeholder;

import com.coremc.core.credits.CreditService;
import com.coremc.core.credits.SkyTokenService;
import com.coremc.core.essence.EssenceManager;
import com.coremc.core.essence.EssenceType;
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
 *   <li>{@code %coremc_credits%} — store Credits (100 = €1)</li>
 *   <li>{@code %coremc_sky_tokens%} — Sky Token balance</li>
 * </ul>
 *
 * Registered by CoreMC only when PlaceholderAPI is installed; persist() is
 * true so a PAPI reload never drops the plugin-provided expansion.
 */
public final class CoremcExpansion extends PlaceholderExpansion {

    private static final String VERSION = "1.0.0";

    private final EssenceManager essences;
    private final EconomyService economy;
    private final CreditService credits;
    private final SkyTokenService tokens;

    public CoremcExpansion(final EssenceManager essences, final EconomyService economy) {
        this(essences, economy, null, null);
    }

    public CoremcExpansion(final EssenceManager essences, final EconomyService economy,
                           final CreditService credits, final SkyTokenService tokens) {
        this.essences = essences;
        this.economy = economy;
        this.credits = credits;
        this.tokens = tokens;
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
            case "credits":
                return credits == null
                        ? EssenceManager.format(0)
                        : CreditService.format(credits.balance(player.getUniqueId()));
            case "sky_tokens":
                return tokens == null
                        ? EssenceManager.format(0)
                        : EssenceManager.format(tokens.balance(player.getUniqueId()));
            default:
                return null;
        }
    }
}
