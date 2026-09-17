package com.coremc.core.shop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Selling maths. ItemStacks cannot be constructed without a live
 * server (they initialise the Bukkit registry on Paper 1.21.11), so
 * the stack-splitting logic is tested through the amounts seam; the
 * ItemStack plumbing itself is covered end-to-end by the live bot
 * journey.
 */
class ShopTransactionsTest {

    @Test
    void removesFromTheFirstStackFirst() {
        final int[] amounts = {32, 16};
        assertEquals(10, ShopTransactions.removeAmounts(amounts, 2, 10));
        assertArrayEquals(new int[]{22, 16}, amounts);
    }

    @Test
    void exhaustingAStackMovesToTheNext() {
        final int[] amounts = {7, 9, 5};
        assertEquals(12, ShopTransactions.removeAmounts(amounts, 3, 12));
        assertArrayEquals(new int[]{0, 4, 5}, amounts);
    }

    @Test
    void removingEverythingClearsAllStacks() {
        final int[] amounts = {7, 9};
        assertEquals(16, ShopTransactions.removeAmounts(amounts, 2, 99));
        assertArrayEquals(new int[]{0, 0}, amounts);
    }

    @Test
    void removingZeroOrNothingIsANoOp() {
        final int[] amounts = {5};
        assertEquals(0, ShopTransactions.removeAmounts(amounts, 1, 0));
        assertArrayEquals(new int[]{5}, amounts);
        assertEquals(0, ShopTransactions.removeAmounts(new int[0], 0, 3));
    }

    @Test
    void moneyFormattingIsStable() {
        assertEquals("$100.00", Money.format(100, "$"));
        assertEquals("$0.25", Money.format(0.25, "$"));
        assertEquals("$69.75", Money.format(69.749999, "$"));
        assertEquals("c12.50", Money.format(12.5, "c"));
        assertEquals(0.01, Money.round(0.005));
        assertEquals(69.75, Money.round(69.749999));
    }
}
