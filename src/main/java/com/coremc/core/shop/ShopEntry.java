package com.coremc.core.shop;

import com.coremc.core.economy.Currency;
import org.bukkit.Material;

/**
 * One buyable shop row from {@code shop.yml}.
 *
 * All balance data (price, currency, amount) comes from the config file —
 * no prices are ever hard-coded in Java.
 */
public record ShopEntry(
        String id,
        Material material,
        String display,
        Currency currency,
        long price,
        int amount) {}
