package com.coremc.core.enchant;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * One entry of a reward table ({@code values.rewards} in
 * {@code enchants.yml}). Each entry rolls independently when its table
 * fires: entries whose chance hits are all granted (tables are small
 * and chances are small, so jackpots stay bounded).
 *
 * Types: ITEM (material + amount), TOKENS / CREDITS (amount),
 * XP (bonus role XP, flat — multipliers do not apply), KEY
 * (crate key id, amount).
 *
 * Amounts accept a plain number or a {@code "min-max"} range string.
 */
public record RewardRoll(
        double chance, RewardType type, String material, int minAmount, int maxAmount, String keyId) {

    public enum RewardType {
        ITEM,
        TOKENS,
        CREDITS,
        XP,
        KEY
    }

    /** Parses one raw table entry; problems go to {@code errors}, fatal ones yield empty. */
    public static Optional<RewardRoll> parse(final Object raw, final List<String> errors, final String where) {
        if (!(raw instanceof Map<?, ?> map)) {
            errors.add(where + ": reward entry must be a map, got " + raw);
            return Optional.empty();
        }
        final double chance = number(map.get("chance"), 1.0);
        if (chance < 0.0 || chance > 1.0) {
            errors.add(where + ": chance must be 0..1, got " + map.get("chance"));
            return Optional.empty();
        }
        final RewardType type;
        try {
            type = RewardType.valueOf(String.valueOf(map.getOrDefault("type", "ITEM"))
                    .trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            errors.add(where + ": unknown reward type " + map.get("type"));
            return Optional.empty();
        }
        int minAmount = 1;
        int maxAmount = 1;
        final Object amount = map.get("amount");
        if (amount instanceof Number number) {
            minAmount = Math.max(1, number.intValue());
            maxAmount = minAmount;
        } else if (amount instanceof String range && range.contains("-")) {
            final String[] parts = range.trim().split("-", 2);
            try {
                minAmount = Math.max(1, Integer.parseInt(parts[0].trim()));
                maxAmount = Math.max(minAmount, Integer.parseInt(parts[1].trim()));
            } catch (final NumberFormatException bad) {
                errors.add(where + ": bad amount range '" + amount + "'");
                return Optional.empty();
            }
        } else if (amount != null) {
            errors.add(where + ": bad amount '" + amount + "'");
            return Optional.empty();
        }
        String material = null;
        String keyId = null;
        if (type == RewardType.ITEM) {
            material = String.valueOf(map.get("material")).trim().toUpperCase(Locale.ROOT);
            if (material.isBlank() || "NULL".equals(material)) {
                errors.add(where + ": ITEM reward needs a material");
                return Optional.empty();
            }
        } else if (type == RewardType.KEY) {
            keyId = String.valueOf(map.get("key")).trim().toLowerCase(Locale.ROOT);
            if (keyId.isBlank() || "null".equals(keyId)) {
                errors.add(where + ": KEY reward needs a key id");
                return Optional.empty();
            }
        }
        return Optional.of(new RewardRoll(chance, type, material, minAmount, maxAmount, keyId));
    }

    /** Rolls an amount in [min, max]. */
    public int rollAmount(final Random random) {
        if (maxAmount <= minAmount) {
            return minAmount;
        }
        return minAmount + random.nextInt(maxAmount - minAmount + 1);
    }

    private static double number(final Object value, final double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
