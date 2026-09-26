package com.coremc.core.credits;

/**
 * Why Credits moved. Every mutation of a Credit balance carries one of
 * these, so the audit log always says who earned or spent what and why.
 *
 * <p>Credits are CoreMC's store currency (100 Credits = €1) but they are
 * deliberately never store-exclusive: quests, island milestones, seasonal
 * progression, events and controlled gameplay rewards all pay Credits
 * through {@link CreditService} — the store only spends them.</p>
 */
public enum CreditReason {

    /** A store purchase (the only reason that spends by design). */
    PURCHASE,

    /** Quest system rewards (the quest system calls in, store code never owns quests). */
    QUEST,

    /** Island milestones — island top season placements and similar. */
    ISLAND_MILESTONE,

    /** Server events. */
    EVENT,

    /** Admin commands (/corecredits give|take|set). */
    ADMIN,

    /** Seasonal progression rewards. */
    SEASONAL,

    /** Voting rewards. */
    VOTE,

    /** A refund of a failed purchase. */
    REFUND;

    /** Parses a reason leniently ({@code null}/unknown → {@link #ADMIN}). */
    public static CreditReason parse(final String text) {
        if (text == null) {
            return ADMIN;
        }
        for (final CreditReason reason : values()) {
            if (reason.name().equalsIgnoreCase(text.trim())) {
                return reason;
            }
        }
        return ADMIN;
    }
}
