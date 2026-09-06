package com.coremc.core.economy;

import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerProfile;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONLY sanctioned way to move CoreMC currency.
 *
 * Contract:
 *  - amounts must be within {@code 1..Long.MAX_VALUE} (deposit/withdraw)
 *    or {@code 0..Long.MAX_VALUE} (set),
 *  - balances can never become negative (withdraw returns false),
 *  - addition overflow is rejected before touching the balance,
 *  - every successful mutation is persisted: premium currencies
 *    (credits, sky tokens) write through immediately, money rides the
 *    dirty-flag autosave/quit/shutdown flush,
 *  - thread-safe via the single-writer profile lifecycle; callers must
 *    operate on a profile obtained from {@link PlayerDataService}.
 *
 * Future systems (shops, crates, upgrades, quests) call this service
 * and never touch {@link PlayerProfile#setBalanceInternal} directly.
 */
public final class EconomyService {

    private final PlayerDataService playerData;
    private final java.util.function.BiConsumer<PlayerProfile, Boolean> persistHook;

    public EconomyService(final PlayerDataService playerData) {
        this.playerData = playerData;
        this.persistHook = null;
    }

    /** Test seam: persistence hook invoked as (profile, writeThrough). */
    EconomyService(final java.util.function.BiConsumer<PlayerProfile, Boolean> persistHook) {
        this.playerData = null;
        this.persistHook = persistHook;
    }

    /** Validates and parses a raw command amount. Throws with a message token on failure. */
    public long parseAmount(final String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new AmountException("invalid");
        }
        if (raw.startsWith("-")) {
            throw new AmountException("negative");
        }
        if (!raw.chars().allMatch(Character::isDigit)) {
            throw new AmountException("invalid");
        }
        try {
            return Long.parseLong(raw);
        } catch (final NumberFormatException overflow) {
            throw new AmountException("too-large");
        }
    }

    public long balanceOf(final PlayerProfile profile, final Currency currency) {
        return profile.balanceOf(currency);
    }

    public boolean canAfford(final PlayerProfile profile, final Currency currency, final long amount) {
        validatePositive(amount);
        return profile.balanceOf(currency) >= amount;
    }

    /** Sets the balance (admin). Amount may be zero. */
    public void setBalance(final PlayerProfile profile, final Currency currency, final long amount) {
        if (amount < 0L) {
            throw new AmountException("negative");
        }
        apply(profile, currency, amount);
    }

    /** Adds {@code amount}. Rejects overflow rather than wrapping. */
    public void deposit(final PlayerProfile profile, final Currency currency, final long amount) {
        validatePositive(amount);
        final long balance = profile.balanceOf(currency);
        if (balance > Long.MAX_VALUE - amount) {
            throw new AmountException("too-large");
        }
        apply(profile, currency, balance + amount);
    }

    /** Removes {@code amount}; returns false (no change) if unaffordable. */
    public boolean withdraw(final PlayerProfile profile, final Currency currency, final long amount) {
        validatePositive(amount);
        final long balance = profile.balanceOf(currency);
        if (balance < amount) {
            return false;
        }
        apply(profile, currency, balance - amount);
        return true;
    }

    /** Balance lookup by UUID (cached or one-off disk load; call off-main if cache may miss). */
    public Optional<Long> balanceOf(final UUID player, final Currency currency) {
        return playerData.cachedOrLoad(player).map(profile -> profile.balanceOf(currency));
    }

    private void apply(final PlayerProfile profile, final Currency currency, final long newBalance) {
        profile.setBalanceInternal(currency, newBalance);
        if (persistHook != null) {
            persistHook.accept(profile, currency.premiumWriteThrough());
        } else {
            playerData.persistAfterEconomyChange(profile, currency.premiumWriteThrough());
        }
    }

    private static void validatePositive(final long amount) {
        if (amount <= 0L) {
            throw new AmountException("invalid");
        }
    }

    /** Validation failure carrying a messages.yml token: economy.error.<token>. */
    public static final class AmountException extends IllegalArgumentException {
        private final String token;

        public AmountException(final String token) {
            super(token);
            this.token = token;
        }

        public String token() {
            return token;
        }
    }
}
