package com.coremc.core.enchant;

import java.util.Locale;
import java.util.Optional;

/**
 * The behaviour kinds of the 90 custom CoreMC enchants.
 *
 * Every custom enchant is data: an {@link Enchant} definition in
 * {@code enchants.yml} names one of these effects plus a trigger and a
 * bag of tuning values. The per-trigger handler classes in this package
 * (one per role activity, never one giant listener) read those values
 * and execute the behaviour. Adding a new behaviour kind means adding
 * one enum constant plus one branch in the matching handler — never a
 * new class per enchant.
 *
 * Value semantics per effect (chance = {@code chanceAt(level)},
 * magnitude = {@code valueAt(level)}):
 *
 * <ul>
 *   <li>HASTE — mining/logging speed potion; magnitude = amplifier
 *       (floored), {@code values.duration-seconds}.</li>
 *   <li>DROP_BONUS — extra drops of what just broke/died; amount =
 *       floored magnitude (min 1); optional
 *       {@code values.only-materials} filter (e.g. saplings).</li>
 *   <li>AOE_BREAK — breaks a cube around the origin block; radius =
 *       floored magnitude; {@code values.max-blocks},
 *       {@code values.break-containers} (default false).</li>
 *   <li>CURRENCY — grants the definition's currency; amount = floored
 *       magnitude (min 1); currency multipliers apply.</li>
 *   <li>MAGNET — route drops straight to the inventory;
 *       {@code values.fallback} = DROP (default) or DESPAWN when the
 *       inventory is full.</li>
 *   <li>SMELT — drop conversions from {@code values.table}
 *       (in-material → out-material), else the built-in ore table.</li>
 *   <li>VEIN — breadth-first break of connected matching blocks
 *       (ores/logs); max blocks = floored magnitude;
 *       {@code values.match} = ORE (default) or LOG.</li>
 *   <li>REWARD_TABLE — rolls {@code values.rewards} (see
 *       {@link RewardRoll}).</li>
 *   <li>COMBO — stacks per valid action inside
 *       {@code values.window-seconds} (max
 *       {@code values.max-stacks}); each stack adds {@code valueAt} %
 *       to {@code values.target} =
 *       XP|DROPS|DAMAGE|CURRENCY|REWARDS|SOULS.</li>
 *   <li>MULTIPLIER — passive; {@code values.target} = XP|TOKENS|
 *       CREDITS|ECONOMY|SOULS means +{@code valueAt} % on those gains;
 *       DROPS means a separate bonus-drop roll (chance + amount);
 *       optional {@code values.max-y} depth gate.</li>
 *   <li>REPLANT — replant crops after harvest;
 *       {@code values.free-chance} (never consumes a seed),
 *       {@code values.delay-ticks}.</li>
 *   <li>FISH_BONUS — {@code values.mode} = EXTRA_CATCH (bonus copies,
 *       count = floored magnitude) or TREASURE (rolls
 *       {@code values.rewards}).</li>
 *   <li>DAMAGE — {@code values.mode} = CRIT (chance × mult),
 *       EXECUTE (+mult when victim HP fraction &lt;
 *       {@code values.threshold}), MOBS (+mult vs
 *       {@code values.mobs}), BOSS (+mult vs {@code values.bosses});
 *       mult = {@code valueAt}.</li>
 *   <li>ULTIMATE — rare + long cooldown; {@code values.mode} =
 *       MINE_BURST|TREE_BLESSING|FISH_BOUNTY|EXECUTE_BURST|
 *       HARVEST_BLESSING|OVERDRIVE; reads radius/max-blocks/cap/
 *       rewards/effects/duration as appropriate.</li>
 *   <li>SOUL — grants souls; amount = floored magnitude (min 1); soul
 *       multipliers apply.</li>
 *   <li>REEL_LUCK — Luck potion while fishing; amplifier = floored
 *       magnitude, {@code values.duration-seconds}.</li>
 *   <li>GROWTH — {@code values.mode} = STUMP (replant + bonemeal the
 *       stump) or NEIGHBOURS (bonemeal crops in
 *       {@code values.radius}).</li>
 *   <li>RECOVERY — heal {@code values.hearts} half-hearts + feed
 *       {@code values.food}; {@code values.extinguish}.</li>
 *   <li>TEMP_BOOST — when any combo stack count reaches
 *       {@code values.trigger-count}, grants {@code values.effects}
 *       (POTION:amplifier:seconds) plus +{@code values.mult-xp} %
 *       XP / +{@code values.mult-drops} % drops for
 *       {@code values.duration-seconds}.</li>
 * </ul>
 */
public enum EnchantEffect {

    HASTE,
    DROP_BONUS,
    AOE_BREAK,
    CURRENCY,
    MAGNET,
    SMELT,
    VEIN,
    REWARD_TABLE,
    COMBO,
    MULTIPLIER,
    REPLANT,
    FISH_BONUS,
    DAMAGE,
    ULTIMATE,
    SOUL,
    REEL_LUCK,
    GROWTH,
    RECOVERY,
    TEMP_BOOST;

    /** Which activity fires the enchant. ANY = every activity handler consults it. */
    public enum Trigger {
        MINE,
        LOG,
        FISH,
        KILL,
        FARM,
        ANY,
        PASSIVE;

        /** Maps a role key to its activity trigger (null for universal). */
        public static Trigger forRole(final String roleKey) {
            return switch (roleKey) {
                case "miner" -> MINE;
                case "logger" -> LOG;
                case "fisher" -> FISH;
                case "slayer" -> KILL;
                case "farmer" -> FARM;
                default -> null;
            };
        }
    }

    /** Case-insensitive parse; empty when unknown. */
    public static Optional<EnchantEffect> parse(final String key) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(key.trim().toUpperCase(Locale.ROOT)));
        } catch (final IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** Case-insensitive trigger parse; empty when unknown. */
    public static Optional<Trigger> parseTrigger(final String key) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Trigger.valueOf(key.trim().toUpperCase(Locale.ROOT)));
        } catch (final IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
