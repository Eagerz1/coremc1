package com.coremc.core.progression;

import com.coremc.core.island.Island;
import java.util.Locale;

/**
 * Centralised gameplay modifiers for CoreMC. Buff/event math is deliberately
 * non-compounding for sensitive outputs: overlapping x2 Slayer Frenzy sources
 * resolve to a capped x2, not x4/x8. Call this service from integrations
 * instead of scattering multiplier logic through listeners.
 */
public final class GameplayModifierService {

    private IslandCoreBuffConfig.ClampConfig clamps =
            new IslandCoreBuffConfig.ClampConfig(2.0, 2.0, 3.0, 3.0, 2.0, 2.0, 2.0, 3.0);
    private IslandCoreBuffService coreBuffs;
    private ServerEventService events;

    public void setClamps(final IslandCoreBuffConfig.ClampConfig clamps) {
        if (clamps != null) {
            this.clamps = clamps;
        }
    }

    public void attachCoreBuffs(final IslandCoreBuffService coreBuffs) {
        this.coreBuffs = coreBuffs;
    }

    public void attachEvents(final ServerEventService events) {
        this.events = events;
    }

    public double killProgressionMultiplier(final Island island) {
        return clampMax(max(
                activeCoreMultiplier(island, "slayer-frenzy", "kill-progression-multiplier", 1.0),
                eventMultiplier("kill-progression-multiplier", 1.0)),
                clamps.killProgressionMax());
    }

    public double omniToolOutputMultiplier(final Island island) {
        return clampMax(max(
                activeCoreMultiplier(island, "slayer-frenzy", "omnitool-output-multiplier", 1.0),
                eventMultiplier("omnitool-output-multiplier", 1.0)),
                clamps.omniToolOutputMax());
    }

    public double discoveryMultiplier(final Island island, final String branch) {
        final double additive = additiveCoreDiscovery(island, branch)
                + additiveEvent("discovery-multiplier");
        return Math.min(clamps.discoveryMax(), 1.0D + additive);
    }

    /** 1.0 = normal interval, 0.5 = twice as fast. */
    public double generatorIntervalModifier(final Island island) {
        final double speed = clampMax(max(
                activeCoreMultiplier(island, "generator-overdrive", "speed-multiplier", 1.0),
                activeStateMultiplier(island, "core-surge", "generator-speed-multiplier", 1.0),
                eventMultiplier("generator-speed-multiplier", 1.0)),
                clamps.generatorSpeedMax());
        return 1.0D / Math.max(1.0D, speed);
    }

    public double islandXpMultiplier(final Island island, final String sourceId) {
        final double additive = additiveCoreXp(island, sourceId) + additiveEvent("island-xp-multiplier");
        return Math.min(clamps.islandXpMax(), 1.0D + additive);
    }

    public double roleXpMultiplier(final Island island) {
        final double additive = additiveCoreGeneric(island, "role-xp-multiplier")
                + additiveEvent("role-xp-multiplier");
        return Math.min(clamps.roleXpMax(), 1.0D + additive);
    }

    public double omniToolProgressionMultiplier(final Island island) {
        final double additive = additiveCoreGeneric(island, "omnitool-progress-multiplier")
                + additiveEvent("omnitool-progress-multiplier");
        return Math.min(clamps.omniToolProgressMax(), 1.0D + additive);
    }

    public double specialFrequencyMultiplier(final Island island, final String branch) {
        final double additive = additiveCoreSpecial(island, branch)
                + additiveEvent("special-frequency-multiplier");
        return Math.min(clamps.specialFrequencyMax(), 1.0D + additive);
    }

    private double activeCoreMultiplier(final Island island, final String buffId,
                                        final String key, final double fallback) {
        if (coreBuffs == null || island == null || !coreBuffs.isEquipped(island, buffId)
                || !coreBuffs.isStateActive(island, buffId)) {
            return fallback;
        }
        final IslandCoreBuffConfig.BuffDef buff = coreBuffs.config().buff(buffId);
        return buff == null ? fallback : buff.number(key, fallback);
    }

    private double activeStateMultiplier(final Island island, final String state,
                                         final String key, final double fallback) {
        if (coreBuffs == null || island == null || !coreBuffs.isStateActive(island, state)) {
            return fallback;
        }
        final IslandCoreBuffConfig.BuffDef buff = coreBuffs.config().buff(state);
        return buff == null ? fallback : buff.number(key, fallback);
    }

    private double additiveCoreDiscovery(final Island island, final String branch) {
        double add = 0.0D;
        add += equippedAdd(island, "core-discovery", "discovery-multiplier");
        add += stateAdd(island, "momentum", "discovery-multiplier");
        add += stateAdd(island, "role-synergy", "discovery-multiplier");
        add += stateAdd(island, "core-surge", "discovery-multiplier");
        add += focusedAdd(island, branch, "discovery-multiplier");
        if ("fishing".equals(normalise(branch))) {
            add += stateAdd(island, "deep-waters", "discovery-multiplier");
        }
        return add;
    }

    private double additiveCoreXp(final Island island, final String sourceId) {
        double add = 0.0D;
        add += stateAdd(island, "momentum", "island-xp-multiplier");
        add += stateAdd(island, "role-synergy", "island-xp-multiplier");
        return add;
    }

    private double additiveCoreSpecial(final Island island, final String branch) {
        double add = 0.0D;
        add += stateAdd(island, "momentum", "special-frequency-multiplier");
        add += stateAdd(island, "core-surge", "special-frequency-multiplier");
        add += focusedAdd(island, branch, "special-frequency-multiplier");
        return add;
    }

    private double additiveCoreGeneric(final Island island, final String key) {
        return stateAdd(island, "momentum", key) + stateAdd(island, "role-synergy", key)
                + stateAdd(island, "core-surge", key);
    }

    private double equippedAdd(final Island island, final String buffId, final String key) {
        if (coreBuffs == null || island == null || !coreBuffs.isEquipped(island, buffId)) {
            return 0.0D;
        }
        final IslandCoreBuffConfig.BuffDef buff = coreBuffs.config().buff(buffId);
        return buff == null ? 0.0D : Math.max(0.0D, buff.number(key, 1.0D) - 1.0D);
    }

    private double stateAdd(final Island island, final String state, final String key) {
        if (coreBuffs == null || island == null || !coreBuffs.isStateActive(island, state)) {
            return 0.0D;
        }
        final IslandCoreBuffConfig.BuffDef buff = coreBuffs.config().buff(state);
        return buff == null ? 0.0D : Math.max(0.0D, buff.number(key, 1.0D) - 1.0D);
    }

    private double focusedAdd(final Island island, final String branch, final String key) {
        if (coreBuffs == null || island == null || !coreBuffs.isEquipped(island, "fortune-cycle")
                || !normalise(branch).equals(coreBuffs.fortuneFocus(island))) {
            return 0.0D;
        }
        final IslandCoreBuffConfig.BuffDef buff = coreBuffs.config().buff("fortune-cycle");
        return buff == null ? 0.0D : Math.max(0.0D, buff.number(key, 1.0D) - 1.0D);
    }

    private double eventMultiplier(final String key, final double fallback) {
        return events == null ? fallback : events.multiplier(key, fallback);
    }

    private double additiveEvent(final String key) {
        return Math.max(0.0D, eventMultiplier(key, 1.0D) - 1.0D);
    }

    private double max(final double... values) {
        double result = 1.0D;
        for (final double value : values) {
            result = Math.max(result, value);
        }
        return result;
    }

    private double clampMax(final double value, final double max) {
        return Math.min(Math.max(1.0D, max), Math.max(1.0D, value));
    }

    private static String normalise(final String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
