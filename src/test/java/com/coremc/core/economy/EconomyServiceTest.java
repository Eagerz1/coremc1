package com.coremc.core.economy;

import com.coremc.core.player.PlayerProfile;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyServiceTest {

    private final AtomicBoolean writeThroughSeen = new AtomicBoolean(false);
    private final EconomyService economy =
            new EconomyService((profile, writeThrough) -> writeThroughSeen.set(writeThrough));

    private PlayerProfile profile() {
        return PlayerProfile.createNew(UUID.randomUUID(), "Trader", 0L);
    }

    @Test
    void parseAmountAcceptsDigits() {
        assertEquals(0L, economy.parseAmount("0"));
        assertEquals(123L, economy.parseAmount("123"));
        assertEquals(Long.MAX_VALUE, economy.parseAmount("9223372036854775807"));
    }

    @Test
    void parseAmountRejectsGarbage() {
        assertToken("invalid", () -> economy.parseAmount("abc"));
        assertToken("invalid", () -> economy.parseAmount(""));
        assertToken("invalid", () -> economy.parseAmount("12.5"));
        assertToken("invalid", () -> economy.parseAmount("1 000"));
        assertToken("negative", () -> economy.parseAmount("-5"));
        assertToken("negative", () -> economy.parseAmount("-999999999999"));
        assertToken("too-large", () -> economy.parseAmount("9223372036854775808"));
        assertToken("too-large", () -> economy.parseAmount("99999999999999999999999999"));
    }

    @Test
    void depositAddsAndWritesThroughForPremiumCurrency() {
        final PlayerProfile profile = profile();
        economy.deposit(profile, Currency.CREDITS, 500L);
        assertEquals(500L, profile.credits());
        assertTrue(writeThroughSeen.get());
    }

    @Test
    void moneyDoesNotWriteThrough() {
        final PlayerProfile profile = profile();
        economy.deposit(profile, Currency.MONEY, 100L);
        assertEquals(100L, profile.money());
        assertFalse(writeThroughSeen.get());
    }

    @Test
    void withdrawRefusesOverdrawAndLeavesBalanceUntouched() {
        final PlayerProfile profile = profile();
        economy.deposit(profile, Currency.CREDITS, 100L);
        assertFalse(economy.withdraw(profile, Currency.CREDITS, 101L));
        assertEquals(100L, profile.credits());
        assertTrue(economy.withdraw(profile, Currency.CREDITS, 100L));
        assertEquals(0L, profile.credits());
    }

    @Test
    void canAffordBoundary() {
        final PlayerProfile profile = profile();
        economy.deposit(profile, Currency.SKY_TOKENS, 10L);
        assertTrue(economy.canAfford(profile, Currency.SKY_TOKENS, 10L));
        assertFalse(economy.canAfford(profile, Currency.SKY_TOKENS, 11L));
    }

    @Test
    void depositOverflowIsRejected() {
        final PlayerProfile profile = profile();
        economy.setBalance(profile, Currency.CREDITS, Long.MAX_VALUE - 5L);
        assertToken("too-large", () -> economy.deposit(profile, Currency.CREDITS, 6L));
        assertEquals(Long.MAX_VALUE - 5L, profile.credits());
    }

    @Test
    void setAllowsZeroAndMax() {
        final PlayerProfile profile = profile();
        economy.setBalance(profile, Currency.CREDITS, 0L);
        assertEquals(0L, profile.credits());
        economy.setBalance(profile, Currency.CREDITS, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, profile.credits());
    }

    @Test
    void invalidMutationAmountsThrow() {
        final PlayerProfile profile = profile();
        assertToken("invalid", () -> economy.deposit(profile, Currency.MONEY, 0L));
        assertToken("invalid", () -> economy.withdraw(profile, Currency.MONEY, 0L));
        assertToken("negative", () -> economy.setBalance(profile, Currency.MONEY, -1L));
    }

    private static void assertToken(final String token, final Runnable action) {
        final EconomyService.AmountException exception =
                assertThrows(EconomyService.AmountException.class, action::run);
        assertEquals(token, exception.token());
    }
}
