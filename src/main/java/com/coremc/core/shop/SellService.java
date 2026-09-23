package com.coremc.core.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Pure valuation maths for the /sell window: how much the contents are
 * worth, how many items sell, and which stacks must go back to the
 * player untouched (custom progression items, catalogue-refused
 * materials). No Bukkit server needed — the callers inject the
 * protection predicate and the price lookup.
 */
public final class SellService {

    /** Result of valuing a sell window's contents. */
    public record Tally(double total, int soldItems, List<ItemStack> returned) {
    }

    /**
     * Values {@code contents}:
     *
     * <ul>
     *   <li>air/empty slots are skipped,</li>
     *   <li>stacks matching {@code protectedItem} (custom-named
     *       progression items) are returned unsold,</li>
     *   <li>materials priced {@code <= 0} are returned unsold,</li>
     *   <li>everything else sells for {@code unitPrice(material) x
     *       amount} (rank multipliers are applied by the caller).</li>
     * </ul>
     */
    public static Tally tally(final ItemStack[] contents,
                              final Predicate<ItemStack> protectedItem,
                              final ToDoubleFunction<Material> unitPrice) {
        double total = 0.0;
        int soldItems = 0;
        final List<ItemStack> returned = new ArrayList<>();
        for (final ItemStack stack : contents) {
            if (stack == null || isAir(stack.getType())) {
                continue;
            }
            if (protectedItem.test(stack)) {
                returned.add(stack);
                continue;
            }
            final double unit = unitPrice.applyAsDouble(stack.getType());
            if (unit <= 0.0) {
                returned.add(stack);
                continue;
            }
            total += unit * stack.getAmount();
            soldItems += stack.getAmount();
        }
        return new Tally(Money.round(total), soldItems, returned);
    }

    /** Plain enum check — Material.isAir() needs the live Bukkit registry. */
    private static boolean isAir(final Material material) {
        return material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }

    private SellService() {
    }
}
