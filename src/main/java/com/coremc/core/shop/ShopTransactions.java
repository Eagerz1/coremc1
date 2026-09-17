package com.coremc.core.shop;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Inventory-side helpers for selling. The stack-splitting maths lives
 * in {@link #removeAmounts(int[], int, int)}, which operates on plain
 * amounts so it is unit-testable without a live server (on Paper
 * 1.21.11 even constructing an {@link ItemStack} initialises the
 * Bukkit registry).
 */
public final class ShopTransactions {

    private ShopTransactions() {
    }

    /** How many of {@code material} the contents hold (stack sizes summed). */
    public static int countSellable(final ItemStack[] contents, final Material material) {
        int total = 0;
        for (final ItemStack stack : contents) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Removes up to {@code wanted} items of {@code material} in place,
     * clearing emptied slots. Returns how many were actually removed.
     */
    public static int removeItems(final ItemStack[] contents, final Material material, final int wanted) {
        final int[] slots = new int[contents.length];
        final int[] amounts = new int[contents.length];
        int found = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            final ItemStack stack = contents[slot];
            if (stack != null && stack.getType() == material) {
                slots[found] = slot;
                amounts[found] = stack.getAmount();
                found++;
            }
        }
        final int removed = removeAmounts(amounts, found, wanted);
        for (int i = 0; i < found; i++) {
            if (amounts[i] == 0) {
                contents[slots[i]] = null;
            } else {
                contents[slots[i]].setAmount(amounts[i]);
            }
        }
        return removed;
    }

    /**
     * Reduces the first {@code count} entries of {@code amounts} in
     * place until {@code wanted} items are taken (or nothing is left),
     * returning how many were taken. Entries that hit zero stay zero.
     */
    static int removeAmounts(final int[] amounts, final int count, final int wanted) {
        int remaining = wanted;
        for (int i = 0; i < count && remaining > 0; i++) {
            final int take = Math.min(amounts[i], remaining);
            amounts[i] -= take;
            remaining -= take;
        }
        return wanted - remaining;
    }
}
