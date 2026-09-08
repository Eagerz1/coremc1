package com.coremc.core.economy;

/**
 * CoreMC currencies. Balances are non-negative longs; the
 * {@link EconomyService} enforces validation and overflow safety.
 *
 * MONEY is the soft in-game currency, CREDITS and SKY_TOKENS are
 * premium/admin-granted currencies and therefore always write through
 * to disk immediately (they must never be lost on a restart).
 */
public enum Currency {
    MONEY("Money", "money", false),
    CREDITS("Credits", "credits", true),
    SKY_TOKENS("Sky Tokens", "skytokens", true);

    private final String displayName;
    private final String messageKey;
    private final boolean premiumWriteThrough;

    Currency(final String displayName, final String messageKey, final boolean premiumWriteThrough) {
        this.displayName = displayName;
        this.messageKey = messageKey;
        this.premiumWriteThrough = premiumWriteThrough;
    }

    public String displayName() {
        return displayName;
    }

    /** messages.yml group used for balance/admin feedback. */
    public String messageKey() {
        return messageKey;
    }

    /** Whether mutations should be flushed to disk immediately. */
    public boolean premiumWriteThrough() {
        return premiumWriteThrough;
    }
}
