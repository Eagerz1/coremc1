package com.coremc.core.gens;

import com.coremc.core.util.GuiText;
import org.bukkit.Material;

/**
 * One generator in the progression (Cobblestone → … → Netherite).
 *
 * <p>Everything a generator is lives here as data, so
 * {@code generators.yml} can be re-balanced (names, blocks, prices,
 * output, intervals, requirements, caps, upgrade path) without a code
 * change.</p>
 *
 * @param id             config id, e.g. {@code iron}
 * @param name           display name, e.g. {@code Iron Generator}
 * @param tier           tier number (1-based, unique, defines order)
 * @param color          {@code &} colour code of the tier
 * @param block          the block placed in the world
 * @param icon           menu icon (defaults to the block)
 * @param price          purchase price in coins
 * @param intervalTicks  seconds between productions, in ticks
 * @param value          coins produced per cycle, per generator
 * @param output         item produced per cycle (null = money only)
 * @param outputAmount   how many of {@link #output} per cycle
 * @param requiredPoints island points needed before it can be bought
 * @param maxPlaced      how many of this generator one island may place
 * @param upgradeTo      id of the next generator, or null at the top
 * @param upgradeCost    coins to upgrade one generator to {@link #upgradeTo}
 */
public record GeneratorTier(String id, String name, int tier, String color, Material block,
                            Material icon, double price, int intervalTicks, double value,
                            Material output, int outputAmount, double requiredPoints,
                            int maxPlaced, String upgradeTo, double upgradeCost) {

    /** Seconds between productions. */
    public int intervalSeconds() {
        return Math.max(1, intervalTicks / 20);
    }

    /** Coloured, bold, small-caps display name for item titles. */
    public String displayName() {
        return color + "&l" + GuiText.caps(name);
    }

    /** Coloured, plain small-caps name for lore lines. */
    public String coloredName() {
        return color + GuiText.caps(name);
    }

    /** Tier as a roman numeral ({@code III}). */
    public String tierNumeral() {
        return GuiText.roman(tier);
    }

    /** Coins produced per hour by a single generator. */
    public double valuePerHour() {
        return value * (3600.0 / intervalSeconds());
    }

    /** Whether this generator can still be upgraded. */
    public boolean hasUpgrade() {
        return upgradeTo != null && !upgradeTo.isBlank();
    }
}
