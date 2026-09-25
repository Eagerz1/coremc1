package com.coremc.core.placeholder;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.essence.EssenceManager;
import com.coremc.core.essence.EssenceType;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * The {@code %x_currency%} alias — whatever essence the server configured
 * as its headline currency ({@code x-currency} in config.yml):
 * {@code total} (default), {@code slayer}, {@code mining} or
 * {@code farming}, rendered comma-formatted like every other balance.
 */
public final class XCurrencyExpansion extends PlaceholderExpansion {

    private static final String VERSION = "1.0.0";

    private final EssenceManager essences;
    private final CoreConfig config;

    public XCurrencyExpansion(final EssenceManager essences, final CoreConfig config) {
        this.essences = essences;
        this.config = config;
    }

    @Override
    public String getIdentifier() {
        return "x";
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
        if (params == null || !params.equalsIgnoreCase("currency")) {
            return null;
        }
        if (player == null) {
            return EssenceManager.format(0);
        }
        final String source = config.xCurrency();
        if ("total".equalsIgnoreCase(source)) {
            return EssenceManager.format(essences.total(player.getUniqueId()));
        }
        final EssenceType type = EssenceType.of(source);
        return type == null
                ? EssenceManager.format(0)
                : EssenceManager.format(essences.balance(player.getUniqueId(), type));
    }
}
