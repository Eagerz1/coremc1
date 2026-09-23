package com.coremc.core.spawner;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure naming and amount maths for stacked spawners and stacked mobs:
 * the "32x Pig Spawner [Normal]" label shared by the item name, the
 * hologram above the block and the mob stack names, plus loot
 * splitting for multiplied drops.
 */
public final class SpawnerStacks {

    private SpawnerStacks() {
    }

    /**
     * Display label of a (possibly stacked) spawner — used as the item
     * name and the hologram above the block:
     * {@code 32x Pig Spawner [Normal]} (single spawners carry no count).
     */
    public static String stackName(final String mobName, final SpawnerVariant variant,
                                   final int amount) {
        final String prefix = amount > 1 ? amount + "x " : "";
        return "&f" + prefix + mobName + " Spawner &8["
                + variantColor(variant) + variant.display() + "&8]";
    }

    /** Chat colour of a variant tier. */
    public static String variantColor(final SpawnerVariant variant) {
        return switch (variant) {
            case NORMAL -> "&f";
            case ADVANCED -> "&a";
            case ANCIENT -> "&6";
            case MYTHIC -> "&d";
        };
    }

    /** Custom name of a stacked mob: {@code 4x Pig}. */
    public static String mobName(final String mobName, final int count) {
        return "&f" + count + "x " + mobName;
    }

    /**
     * Splits a total item amount into inventory-sized chunks: 100 with
     * a max stack of 64 becomes [64, 36]. Used to multiply the loot of
     * a killed mob stack without overflowing item stacks.
     */
    public static List<Integer> splitAmounts(final int total, final int maxStack) {
        final int cap = Math.max(1, maxStack);
        final List<Integer> parts = new ArrayList<>();
        int remaining = total;
        while (remaining > 0) {
            final int part = Math.min(cap, remaining);
            parts.add(part);
            remaining -= part;
        }
        return parts;
    }
}
