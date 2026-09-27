package com.coremc.core.progress;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * The anti-exploit rules, written as pure predicates so every one of
 * them is unit-tested without a server.
 *
 * <p>Nothing here knows about Bukkit types: the bridge passes in the
 * few facts that matter (game mode name, world name, entity type name,
 * whether the block was player-placed) and gets a yes/no back. That
 * keeps the rules honest and testable, and means the same rules apply
 * to events coming from adapters on other branches.</p>
 */
public final class ProgressGuards {

    /** Game modes whose actions count. */
    private static final Set<String> COUNTED_GAME_MODES = Set.of("SURVIVAL", "ADVENTURE");

    /** Entities that are scenery, not mobs — holograms, frames, markers. */
    private static final Set<String> NON_MOB_ENTITIES = Set.of(
            "PLAYER", "ARMOR_STAND", "ITEM_DISPLAY", "BLOCK_DISPLAY", "TEXT_DISPLAY",
            "MARKER", "INTERACTION", "ITEM_FRAME", "GLOW_ITEM_FRAME", "PAINTING",
            "AREA_EFFECT_CLOUD", "FALLING_BLOCK", "DROPPED_ITEM", "ITEM", "EXPERIENCE_ORB");

    private ProgressGuards() {
    }

    /** Creative and spectator actions never count; admin builds are not progression. */
    public static boolean validGameMode(final String gameMode) {
        return gameMode != null && COUNTED_GAME_MODES.contains(gameMode.toUpperCase(Locale.ROOT));
    }

    /**
     * True when the world counts. An empty allow-list means "every
     * world except the denied ones", which is the default.
     */
    public static boolean validWorld(final String world, final Collection<String> allowed,
                                     final Collection<String> denied) {
        if (world == null || world.isBlank()) {
            return false;
        }
        final String needle = world.toLowerCase(Locale.ROOT);
        if (denied != null && denied.stream().anyMatch(name -> name.equalsIgnoreCase(needle))) {
            return false;
        }
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return allowed.stream().anyMatch(name -> name.equalsIgnoreCase(needle));
    }

    /** Display entities, frames and players are never Slayer kills. */
    public static boolean validMobType(final String entityType) {
        return entityType != null && !entityType.isBlank()
                && !NON_MOB_ENTITIES.contains(entityType.toUpperCase(Locale.ROOT));
    }

    /**
     * Mining only counts for blocks the world generated (or the Mining
     * Cube produced) — never for a block the player just placed.
     */
    public static boolean countableMining(final boolean playerPlaced, final boolean generatedBlock) {
        return !playerPlaced && generatedBlock;
    }

    /** Harvests only count when the crop was actually ripe. */
    public static boolean countableHarvest(final boolean playerPlaced, final boolean fullyGrown) {
        // CoreMC crops regrow instantly, so a planted crop is legitimate —
        // what matters is that it was ripe when it was harvested.
        return fullyGrown && !playerPlaced;
    }

    /** A kill counts when the player caused it and the victim is a real mob. */
    public static boolean countableKill(final boolean playerCaused, final String entityType) {
        return playerCaused && validMobType(entityType);
    }

    /** Amounts must be sane: positive and not an overflowed number. */
    public static boolean validAmount(final long amount) {
        return amount > 0 && amount < 1_000_000L;
    }

    /**
     * The whole gate for a world action, in one call.
     *
     * @param gameMode the player's game mode name
     * @param world    the world the action happened in
     * @param allowed  optional allow-list of worlds
     * @param denied   optional deny-list of worlds
     * @param amount   the amount being recorded
     */
    public static boolean validWorldAction(final String gameMode, final String world,
                                           final Collection<String> allowed,
                                           final Collection<String> denied, final long amount) {
        return validGameMode(gameMode) && validWorld(world, allowed, denied) && validAmount(amount);
    }
}
