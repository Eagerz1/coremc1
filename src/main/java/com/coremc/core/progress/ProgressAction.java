package com.coremc.core.progress;

import java.util.Locale;

/**
 * Every authoritative progression action CoreMC can record, with a
 * stable string id that configs, saved data and adapters refer to.
 *
 * <p>The ids are the contract: renaming an enum constant is allowed,
 * renaming its {@link #id()} is not — saved Collection and Achievement
 * data is keyed by these strings.</p>
 *
 * <p>Two flags shape the anti-exploit policy:</p>
 * <ul>
 *   <li>{@link #passive()} — output that accrues without the player
 *       doing anything (generator payouts). Collections may track it,
 *       but it must never dominate completion, so passive actions are
 *       weighted down and capped by the tracking services.</li>
 *   <li>{@link #rateLimited()} — cheap repeatable procs (enchant
 *       triggers). These are deduplicated on a cooldown so spamming
 *       the same proc forever cannot farm progression.</li>
 * </ul>
 */
public enum ProgressAction {

    // mining -----------------------------------------------------------
    MINE_BLOCK("mine_block"),
    RICH_VEIN("rich_vein"),

    // farming ----------------------------------------------------------
    HARVEST_CROP("harvest_crop"),

    // fishing ----------------------------------------------------------
    FISH_CATCH("fish_catch"),
    FISH_TREASURE("fish_treasure"),

    // slayer -----------------------------------------------------------
    SLAYER_KILL("slayer_kill"),
    ELITE_KILL("elite_kill"),
    CHAMPION_KILL("champion_kill"),
    SLAYER_FRENZY("slayer_frenzy"),
    SLAYER_DROP("slayer_drop"),

    // discoveries ------------------------------------------------------
    DISCOVERY("discovery"),

    // generators -------------------------------------------------------
    GENERATOR_PLACE("generator_place"),
    GENERATOR_UNLOCK("generator_unlock"),
    GENERATOR_UPGRADE("generator_upgrade"),
    GENERATOR_OVERDRIVE("generator_overdrive"),
    GENERATOR_OUTPUT("generator_output", true, false),

    // spawners ---------------------------------------------------------
    SPAWNER_UNLOCK("spawner_unlock"),
    SPAWNER_PLACE("spawner_place"),
    SPAWNER_UPGRADE("spawner_upgrade"),
    SPAWNER_KILL("spawner_kill"),
    SPAWNER_DROP("spawner_drop"),

    // companions (adapter) ---------------------------------------------
    COMPANION_DISCOVER("companion_discover"),
    COMPANION_MILESTONE("companion_milestone"),
    COMPANION_MAXED("companion_maxed"),

    // roles + omnitools (adapter) --------------------------------------
    ROLE_MILESTONE("role_milestone"),
    OMNITOOL_UNLOCK("omnitool_unlock"),
    OMNITOOL_MILESTONE("omnitool_milestone"),
    OMNITOOL_ENCHANT_UNLOCK("omnitool_enchant_unlock"),
    OMNITOOL_ENCHANT_PROC("omnitool_enchant_proc", false, true),

    // islands, quests, events, seasons ----------------------------------
    ISLAND_LEVEL("island_level"),
    ISLAND_UPGRADE("island_upgrade"),
    QUEST_COMPLETE("quest_complete"),
    ISLAND_CHALLENGE("island_challenge"),
    EVENT_PARTICIPATE("event_participate"),
    SEASON_LEVEL("season_level");

    private final String id;
    private final boolean passive;
    private final boolean rateLimited;

    ProgressAction(final String id) {
        this(id, false, false);
    }

    ProgressAction(final String id, final boolean passive, final boolean rateLimited) {
        this.id = id;
        this.passive = passive;
        this.rateLimited = rateLimited;
    }

    /** Stable config/storage id, e.g. {@code mine_block}. */
    public String id() {
        return id;
    }

    /** True for output that accrues while the player does nothing. */
    public boolean passive() {
        return passive;
    }

    /** True for cheap repeatable procs that must not be farmed. */
    public boolean rateLimited() {
        return rateLimited;
    }

    /** Action for a config id (case-insensitive), or null. */
    public static ProgressAction of(final String id) {
        if (id == null) {
            return null;
        }
        final String needle = id.trim().toLowerCase(Locale.ROOT);
        for (final ProgressAction action : values()) {
            if (action.id.equals(needle)) {
                return action;
            }
        }
        return null;
    }
}
