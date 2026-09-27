package com.coremc.core.market;

import java.util.Locale;

/**
 * The Black Market offer pools. COMMON..LEGENDARY follow the store
 * rarity colours; COSMETIC and SEASONAL are the two optional flavour
 * pools — the only pools allowed to charge Credits (cosmetics only,
 * never gameplay power).
 */
public enum MarketRarity {

    COMMON("&7", "Common"),
    RARE("&d", "Rare"),
    EPIC("&5", "Epic"),
    LEGENDARY("&6", "Legendary"),
    COSMETIC("&e", "Cosmetic"),
    SEASONAL("&b", "Seasonal");

    private final String color;
    private final String display;

    MarketRarity(final String color, final String display) {
        this.color = color;
        this.display = display;
    }

    /** The rarity's colour code (matches the store rarity language). */
    public String color() {
        return color;
    }

    /** Human name: {@code Legendary}. */
    public String display() {
        return display;
    }

    /** Lenient parse; null for unknown values. */
    public static MarketRarity parse(final String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }
}
