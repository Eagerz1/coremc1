package com.coremc.core.reward;

/**
 * What a crate / lootbox / bundle reward pays out. One shared model for
 * the whole store architecture — crates, lootboxes and bundles all roll
 * and deliver through the same types.
 */
public enum RewardType {

    /** Coins into the CoreMC economy. */
    MONEY,

    /** Sky Tokens into the token balance. */
    SKY_TOKENS,

    /** Credits into the Credit balance (controlled gameplay rewards). */
    CREDITS,

    /** A physical PDC-identified crate key ({@code id} = key id). */
    KEY,

    /** A physical PDC-identified lootbox ({@code id} = lootbox id). */
    LOOTBOX,

    /** A PDC-tagged custom material item ({@code id} = material tag, e.g. core_fragment). */
    ITEM,

    /** A console command with {@code %player%} substituted. */
    COMMAND;

    /** Parses a type ({@code sky_tokens} / {@code tokens} both work); null when unknown. */
    public static RewardType parse(final String text) {
        if (text == null) {
            return null;
        }
        final String normalized = text.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        if ("TOKENS".equals(normalized)) {
            return SKY_TOKENS;
        }
        for (final RewardType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
