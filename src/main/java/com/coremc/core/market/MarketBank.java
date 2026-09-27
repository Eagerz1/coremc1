package com.coremc.core.market;

import com.coremc.core.credits.CreditReason;
import com.coremc.core.credits.CreditService;
import com.coremc.core.credits.SkyTokenService;
import com.coremc.core.shop.EconomyService;
import java.util.UUID;

/**
 * Multi-currency payment for the Black Market: Money (primary),
 * Sky Tokens and Credits. One debit is all-or-nothing — if a later
 * currency unexpectedly refuses after an earlier one was taken, the
 * earlier ones are refunded, so a failed purchase never costs
 * anything and no balance can go negative (each underlying service
 * already refuses overdrafts).
 */
public class MarketBank {

    private final EconomyService economy;
    private final SkyTokenService tokens;
    private final CreditService credits;

    public MarketBank(final EconomyService economy, final SkyTokenService tokens,
                      final CreditService credits) {
        this.economy = economy;
        this.tokens = tokens;
        this.credits = credits;
    }

    /**
     * True when every currency of {@code cost} is payable right now.
     * A cost that needs a currency whose service is missing is never
     * affordable (fail closed).
     */
    public boolean canAfford(final UUID player, final MarketCost cost) {
        if (cost.money() > 0 && (economy == null || !economy.has(player, cost.money()))) {
            return false;
        }
        if (cost.tokens() > 0 && (tokens == null || tokens.balance(player) < cost.tokens())) {
            return false;
        }
        return cost.credits() <= 0
                || (credits != null && credits.has(player, cost.credits()));
    }

    /**
     * Debits every currency exactly once. Returns false — with every
     * already-taken currency refunded — when any leg refuses.
     */
    public boolean debit(final UUID player, final MarketCost cost, final String detail) {
        if (!canAfford(player, cost)) {
            return false;
        }
        boolean tookMoney = false;
        boolean tookTokens = false;
        if (cost.money() > 0) {
            if (!economy.withdraw(player, cost.money())) {
                return false;
            }
            tookMoney = true;
        }
        if (cost.tokens() > 0) {
            if (!tokens.take(player, cost.tokens())) {
                refund(player, cost, tookMoney, false);
                return false;
            }
            tookTokens = true;
        }
        if (cost.credits() > 0
                && !credits.take(player, cost.credits(), CreditReason.PURCHASE, detail)) {
            refund(player, cost, tookMoney, tookTokens);
            return false;
        }
        return true;
    }

    /** Refunds a full cost (used when a reservation must unwind). */
    public void refundAll(final UUID player, final MarketCost cost) {
        refund(player, cost, cost.money() > 0, cost.tokens() > 0);
        if (cost.credits() > 0 && credits != null) {
            credits.add(player, cost.credits(), CreditReason.ADMIN, "black market refund");
        }
    }

    private void refund(final UUID player, final MarketCost cost, final boolean money,
                        final boolean tokensTaken) {
        if (money && economy != null) {
            economy.deposit(player, cost.money());
        }
        if (tokensTaken && tokens != null) {
            tokens.add(player, cost.tokens());
        }
    }
}
