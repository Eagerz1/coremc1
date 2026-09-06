package com.coremc.core.spawner;

import com.coremc.core.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Immutable spawner type loaded from {@code config.yml spawners:<id>}.
 *
 * A spawner is <b>unlocked</b> by killing {@link #requiredKills()} of its
 * entity type (persisted per player), then <b>purchased</b> for Sky Tokens.
 */
public record SpawnerDefinition(
        String id,
        String display,
        EntityType entityType,
        Material icon,
        long requiredKills,
        long priceSkyTokens) {

    /** Kill-counter key used in the player profile — lowercase entity name. */
    public String killKey() {
        return entityType.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Legacy-coloured display name. */
    public String colouredDisplay() {
        return ColorUtil.colorize(display);
    }
}
