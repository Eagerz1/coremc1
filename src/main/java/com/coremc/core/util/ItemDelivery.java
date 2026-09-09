package com.coremc.core.util;

import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Atomic item delivery (audit fix): before this existed, every purchase
 * path withdrew the currency first and then overflowed into the ender
 * chest — if BOTH were full the paid-for items were silently destroyed.
 *
 * {@link #deliver} stages through inventory -> ender chest and, if the
 * item still does not fit, restores both inventories to their exact
 * pre-attempt state and returns false, letting the caller refund the
 * purchase price. Nothing is ever destroyed or dropped on this path.
 *
 * Both the snapshots AND the passed stacks are deep-cloned: Bukkit's
 * {@code addItem} mutates live stacks in place (partial top-ups) and
 * consumes the passed stacks, so reference snapshots would roll back
 * mutated state (dupe vector) and leave callers holding eaten stacks.
 */
public final class ItemDelivery {

    private ItemDelivery() {
    }

    /**
     * Tries to give stacks to the player: inventory first, ender chest as
     * overflow. On total failure both inventories are rolled back to their
     * pre-call contents. The passed stacks are never mutated.
     *
     * @return true if every stack was placed, false if nothing could fit
     *         (state fully restored)
     */
    public static boolean deliver(final Player player, final ItemStack... stacks) {
        final ItemStack[] inventorySnapshot = cloneContents(player.getInventory().getContents());
        final ItemStack[] enderSnapshot = cloneContents(player.getEnderChest().getContents());

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(cloneContents(stacks));
        if (!overflow.isEmpty()) {
            overflow = player.getEnderChest().addItem(overflow.values().toArray(ItemStack[]::new));
        }
        if (!overflow.isEmpty()) {
            player.getInventory().setContents(inventorySnapshot);
            player.getEnderChest().setContents(enderSnapshot);
            return false;
        }
        return true; // placed fully (inventory and/or ender chest)
    }

    /** Variant that reports whether the ender chest absorbed the delivery. */
    public static Result deliverDetailed(final Player player, final ItemStack... stacks) {
        final ItemStack[] inventorySnapshot = cloneContents(player.getInventory().getContents());
        final ItemStack[] enderSnapshot = cloneContents(player.getEnderChest().getContents());

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(cloneContents(stacks));
        boolean usedEnderChest = false;
        if (!overflow.isEmpty()) {
            usedEnderChest = true;
            overflow = player.getEnderChest().addItem(overflow.values().toArray(ItemStack[]::new));
        }
        if (!overflow.isEmpty()) {
            player.getInventory().setContents(inventorySnapshot);
            player.getEnderChest().setContents(enderSnapshot);
            return Result.FAILED;
        }
        return usedEnderChest ? Result.DELIVERED_TO_ENDER_CHEST : Result.DELIVERED;
    }

    /** Deep copy: Bukkit hands out live-stack arrays, never safe snapshots. */
    private static ItemStack[] cloneContents(final ItemStack[] contents) {
        final ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    public enum Result {
        DELIVERED,
        DELIVERED_TO_ENDER_CHEST,
        FAILED
    }
}
