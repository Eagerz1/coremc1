package com.coremc.core.progress.reward;

import java.util.Locale;

/**
 * What a Collection milestone or an Achievement hands over.
 *
 * <p>The design rule for both systems: prefer rewards that unlock
 * <em>content</em> (recipes, mechanics, cosmetics, titles, access
 * flags, new Collection tiers) over permanent stat inflation. Anything
 * that is an item or a currency is small, and is claimed by hand so it
 * can never be dropped on the floor.</p>
 *
 * <p>{@link #permanent()} rewards apply automatically the moment they
 * are earned and are remembered forever; the rest are manual claims
 * processed exactly once.</p>
 */
public enum RewardType {

    /** Unlocks a Collection-locked recipe (permanent). */
    RECIPE("recipe", true),
    /** A generic permanent unlock/access flag, e.g. an Elite challenge tier. */
    UNLOCK("unlock", true),
    /** A cosmetic unlock, e.g. a generator skin or companion effect. */
    COSMETIC("cosmetic", true),
    /** A chat/profile title. */
    TITLE("title", true),
    /** A profile tag or badge. */
    TAG("tag", true),
    /** Opens a further Collection tier (permanent). */
    COLLECTION_TIER("collection_tier", true),
    /** A small, sensible statistical bonus (permanent, deliberately rare). */
    STAT("stat", true),

    /** A stack of items (manual claim, parked when the inventory is full). */
    ITEM("item", false),
    /** CoreMC coins (manual claim). */
    COINS("coins", false),
    /** Sky Tokens through the reward adapter (manual claim). */
    SKY_TOKENS("sky_tokens", false),
    /** Credits through the reward adapter (manual claim). */
    CREDITS("credits", false),
    /** A crate key through the reward adapter (manual claim). */
    KEY("key", false),
    /** Season Journey XP through the adapter (manual claim). */
    JOURNEY_XP("journey_xp", false);

    private final String id;
    private final boolean permanent;

    RewardType(final String id, final boolean permanent) {
        this.id = id;
        this.permanent = permanent;
    }

    /** Stable config/storage id. */
    public String id() {
        return id;
    }

    /** True when the reward applies automatically and is kept forever. */
    public boolean permanent() {
        return permanent;
    }

    /** True when the player has to claim it by hand (items and currencies). */
    public boolean manual() {
        return !permanent;
    }

    /** True when the amount field is meaningful. */
    public boolean amountBased() {
        return this == ITEM || this == COINS || this == SKY_TOKENS
                || this == CREDITS || this == KEY || this == JOURNEY_XP;
    }

    /** Type for a config id (case-insensitive), or null. */
    public static RewardType of(final String id) {
        if (id == null) {
            return null;
        }
        final String needle = id.trim().toLowerCase(Locale.ROOT);
        for (final RewardType type : values()) {
            if (type.id.equals(needle)) {
                return type;
            }
        }
        return null;
    }
}
