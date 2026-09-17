package com.coremc.core.shop;

import org.bukkit.Material;

/**
 * One purchasable/sellable shop entry. Prices are per single item; a
 * sell price of {@code 0} means the item cannot be sold (and a buy
 * price of {@code 0} means it cannot be bought).
 *
 * @param material  the traded material
 * @param buyPrice  coins to buy one, &ge; 0
 * @param sellPrice coins received for selling one, &ge; 0 and &le; buyPrice
 */
public record ShopItem(Material material, double buyPrice, double sellPrice) {

    public boolean buyable() {
        return buyPrice > 0;
    }

    public boolean sellable() {
        return sellPrice > 0;
    }

    /** Human-friendly material name: {@code COBBLESTONE_STAIRS -> Cobblestone Stairs}. */
    public String displayName() {
        final String[] parts = material.name().toLowerCase().split("_");
        final StringBuilder builder = new StringBuilder();
        for (final String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
