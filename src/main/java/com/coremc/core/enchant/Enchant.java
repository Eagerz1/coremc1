package com.coremc.core.enchant;

import com.coremc.core.economy.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One custom enchant definition: pure immutable data parsed from
 * {@code enchants.yml} by {@link EnchantRegistry}.
 *
 * Level math is linear and centrally defined so the GUI, the purchase
 * flow and the behaviour handlers can never disagree:
 * {@code chanceAt} / {@code valueAt} interpolate base + scale ×
 * (level-1), with chance additionally capped. Costs are an explicit
 * per-level list (built from base × growth^(n-1) when the config uses
 * the compact formula form).
 */
public record Enchant(
        String id,
        String role,
        String display,
        String description,
        String icon,
        int maxLevel,
        Currency currency,
        List<Long> costs,
        EnchantEffect effect,
        EnchantEffect.Trigger trigger,
        double chanceBase,
        double chanceScale,
        double chanceCap,
        double valueBase,
        double valueScale,
        long cooldownSeconds,
        int minRoleLevel,
        Map<String, Object> values) {

    public Enchant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(trigger, "trigger");
        if (maxLevel < 1) {
            throw new IllegalArgumentException("max-level must be >= 1 for " + id);
        }
        Objects.requireNonNull(costs, "costs");
        if (costs.size() != maxLevel) {
            throw new IllegalArgumentException(
                    "costs list size " + costs.size() + " != max-level " + maxLevel + " for " + id);
        }
        costs = List.copyOf(costs);
        values = values == null ? Map.of() : Map.copyOf(values);
        if (minRoleLevel < 1) {
            throw new IllegalArgumentException("min-role-level must be >= 1 for " + id);
        }
    }

    /** Sky-token-style price of buying exactly {@code level} (1-based). */
    public long costForLevel(final int level) {
        if (level < 1 || level > maxLevel) {
            throw new IllegalArgumentException("level " + level + " out of range for " + id);
        }
        return costs.get(level - 1);
    }

    /** Activation chance at {@code level} (0 when unowned). */
    public double chanceAt(final int level) {
        if (level <= 0) {
            return 0.0;
        }
        return Math.min(chanceCap, chanceBase + chanceScale * (level - 1));
    }

    /** Primary magnitude at {@code level} (0 when unowned). */
    public double valueAt(final int level) {
        if (level <= 0) {
            return 0.0;
        }
        return valueBase + valueScale * (level - 1);
    }

    /** Whether this enchant belongs to the universal track. */
    public boolean universal() {
        return "universal".equals(role);
    }
}
