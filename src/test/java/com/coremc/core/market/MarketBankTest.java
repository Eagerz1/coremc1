package com.coremc.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.credits.CreditReason;
import com.coremc.core.credits.CreditService;
import com.coremc.core.credits.SkyTokenService;
import com.coremc.core.shop.EconomyService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Multi-currency affordability and exactly-once debits: no leg is
 * charged when any leg cannot pay, refunds restore everything, and
 * no balance can ever go negative.
 */
class MarketBankTest {

    private static final UUID PLAYER = MarketTestSupport.BUYER;

    @Test
    void affordabilityChecksEveryCurrency() {
        final EconomyService economy = MarketTestSupport.economy(1_000_000);
        final SkyTokenService tokens = MarketTestSupport.tokens();
        final CreditService credits = MarketTestSupport.credits();
        tokens.add(PLAYER, 1_500);
        credits.add(PLAYER, 100, CreditReason.ADMIN);
        final MarketBank bank = new MarketBank(economy, tokens, credits);

        assertTrue(bank.canAfford(PLAYER, new MarketCost(500_000, 1_000, 50)));
        assertFalse(bank.canAfford(PLAYER, new MarketCost(1_000_001, 0, 0)));
        assertFalse(bank.canAfford(PLAYER, new MarketCost(500_000, 1_501, 0)));
        assertFalse(bank.canAfford(PLAYER, new MarketCost(500_000, 0, 101)));
    }

    @Test
    void missingCurrencyServicesFailClosed() {
        final MarketBank bank = new MarketBank(MarketTestSupport.economy(1_000_000), null, null);
        assertTrue(bank.canAfford(PLAYER, new MarketCost(500_000, 0, 0)));
        assertFalse(bank.canAfford(PLAYER, new MarketCost(500_000, 100, 0)),
                "a tokens price without a tokens service is never affordable");
        assertFalse(bank.canAfford(PLAYER, new MarketCost(0, 0, 100)));
        assertFalse(bank.debit(PLAYER, new MarketCost(0, 0, 100), "test"));
    }

    @Test
    void debitTakesEveryLegExactlyOnce() {
        final EconomyService economy = MarketTestSupport.economy(1_000_000);
        final SkyTokenService tokens = MarketTestSupport.tokens();
        final CreditService credits = MarketTestSupport.credits();
        tokens.add(PLAYER, 2_000);
        credits.add(PLAYER, 150, CreditReason.ADMIN);
        final MarketBank bank = new MarketBank(economy, tokens, credits);

        assertTrue(bank.debit(PLAYER, new MarketCost(250_000, 1_500, 100), "combo"));
        assertEquals(750_000, economy.balance(PLAYER), 0.001);
        assertEquals(500, tokens.balance(PLAYER));
        assertEquals(50, credits.balance(PLAYER));
    }

    @Test
    void aFailedDebitCostsNothingAnywhere() {
        final EconomyService economy = MarketTestSupport.economy(1_000_000);
        final SkyTokenService tokens = MarketTestSupport.tokens();
        tokens.add(PLAYER, 100); // NOT enough for the tokens leg
        final MarketBank bank = new MarketBank(economy, tokens, MarketTestSupport.credits());

        assertFalse(bank.debit(PLAYER, new MarketCost(250_000, 1_500, 0), "combo"));
        assertEquals(1_000_000, economy.balance(PLAYER), 0.001, "money leg untouched");
        assertEquals(100, tokens.balance(PLAYER));
    }

    @Test
    void refundAllRestoresEveryLeg() {
        final EconomyService economy = MarketTestSupport.economy(1_000_000);
        final SkyTokenService tokens = MarketTestSupport.tokens();
        final CreditService credits = MarketTestSupport.credits();
        tokens.add(PLAYER, 2_000);
        credits.add(PLAYER, 150, CreditReason.ADMIN);
        final MarketBank bank = new MarketBank(economy, tokens, credits);
        final MarketCost cost = new MarketCost(250_000, 1_500, 100);

        assertTrue(bank.debit(PLAYER, cost, "combo"));
        bank.refundAll(PLAYER, cost);
        assertEquals(1_000_000, economy.balance(PLAYER), 0.001);
        assertEquals(2_000, tokens.balance(PLAYER));
        assertEquals(150, credits.balance(PLAYER));
    }

    @Test
    void balancesNeverGoNegative() {
        final EconomyService economy = MarketTestSupport.economy(100);
        final MarketBank bank = new MarketBank(economy, null, null);
        assertFalse(bank.debit(PLAYER, new MarketCost(101, 0, 0), "over"));
        assertTrue(economy.balance(PLAYER) >= 0);
        assertTrue(bank.debit(PLAYER, new MarketCost(100, 0, 0), "exact"));
        assertEquals(0, economy.balance(PLAYER), 0.001);
        assertFalse(bank.debit(PLAYER, new MarketCost(1, 0, 0), "broke"));
        assertEquals(0, economy.balance(PLAYER), 0.001);
    }
}
