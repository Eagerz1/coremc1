package com.coremc.core.market;

import java.util.Random;

/**
 * A configured price band per currency: each rotation picks one
 * concrete {@link MarketCost} inside the band (inclusive), rounded to
 * a sensible step so prices never look like $743,291. This is the
 * whole "dynamic pricing" story — simple ranges, no economy
 * simulation.
 */
public record CostBand(long moneyMin, long moneyMax, long tokensMin, long tokensMax,
                       long creditsMin, long creditsMax) {

    public CostBand {
        if (moneyMin < 0 || tokensMin < 0 || creditsMin < 0) {
            throw new IllegalArgumentException("negative price");
        }
        if (moneyMax < moneyMin || tokensMax < tokensMin || creditsMax < creditsMin) {
            throw new IllegalArgumentException("price band max below min");
        }
        if (moneyMax == 0 && tokensMax == 0 && creditsMax == 0) {
            throw new IllegalArgumentException("offer has no price");
        }
    }

    /** A fixed (band-less) cost. */
    public static CostBand fixed(final long money, final long tokens, final long credits) {
        return new CostBand(money, money, tokens, tokens, credits, credits);
    }

    /** True when this band charges Credits at all. */
    public boolean chargesCredits() {
        return creditsMax > 0;
    }

    /** Picks this rotation's concrete price inside the band. */
    public MarketCost resolve(final Random random) {
        return new MarketCost(pick(moneyMin, moneyMax, random),
                pick(tokensMin, tokensMax, random),
                pick(creditsMin, creditsMax, random));
    }

    private static long pick(final long min, final long max, final Random random) {
        if (max <= min) {
            return min;
        }
        final long raw = min + (long) Math.floor(random.nextDouble() * (max - min + 1));
        final long clamped = Math.min(max, Math.max(min, raw));
        return round(clamped, min, max);
    }

    /**
     * Rounds a picked price to a clean step (1% of the band width,
     * snapped to a power-of-ten-ish step) while staying inside the
     * band.
     */
    static long round(final long value, final long min, final long max) {
        final long width = max - min;
        if (width < 100) {
            return value;
        }
        long step = 1;
        while (step * 100 < width) {
            step *= 10;
        }
        final long rounded = Math.round(value / (double) step) * step;
        return Math.min(max, Math.max(min, rounded));
    }
}
