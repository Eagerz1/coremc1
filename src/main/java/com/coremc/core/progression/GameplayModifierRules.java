package com.coremc.core.progression;

/** Pure combination rules: sensitive multipliers use max-not-product, additive
 * frequencies use capped additive behaviour. */
public final class GameplayModifierRules {

    private GameplayModifierRules() {
    }

    public static double cappedMax(final double cap, final double... values) {
        double result = 1.0D;
        for (final double value : values) {
            result = Math.max(result, value);
        }
        return Math.min(Math.max(1.0D, cap), Math.max(1.0D, result));
    }

    public static double cappedAdditive(final double cap, final double... multipliers) {
        double extra = 0.0D;
        for (final double multiplier : multipliers) {
            extra += Math.max(0.0D, multiplier - 1.0D);
        }
        return Math.min(Math.max(1.0D, cap), 1.0D + extra);
    }
}
