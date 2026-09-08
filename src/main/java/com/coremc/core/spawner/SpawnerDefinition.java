package com.coremc.core.spawner;

import com.coremc.core.util.ColorUtil;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * One mob's spawner progression lane loaded from
 * {@code config.yml spawners:<id>}.
 *
 * A mob has an ordered list of {@link SpawnerTier}s unlocked by kills of
 * its entity type (counters persist per player). Legacy single-tier
 * config sections ({@code required-kills}/{@code price} directly under
 * the mob) still load — they become a lane with exactly one tier.
 */
public record SpawnerDefinition(
        String id,
        String display,
        EntityType entityType,
        Material icon,
        List<SpawnerTier> tiers) {

    public SpawnerDefinition {
        tiers = List.copyOf(tiers == null ? List.of() : tiers);
    }

    /** Kill-counter key used in the player profile — lowercase entity name. */
    public String killKey() {
        return entityType.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Legacy-coloured mob display name (menus). */
    public String colouredDisplay() {
        return ColorUtil.colorize(display);
    }

    /** Tier by 1-based index. */
    public Optional<SpawnerTier> tier(final int index) {
        if (index < 1 || index > tiers.size()) {
            return Optional.empty();
        }
        return Optional.of(tiers.get(index - 1));
    }

    /** Number of tiers currently unlocked at {@code kills} recorded kills. */
    public int unlockedTierCount(final long kills) {
        int count = 0;
        for (final SpawnerTier tier : tiers) {
            if (kills >= tier.requiredKills()) {
                count++;
            }
        }
        return count;
    }

    /** First tier not yet unlocked at {@code kills} (empty when the lane is maxed). */
    public Optional<SpawnerTier> nextLockedTier(final long kills) {
        for (final SpawnerTier tier : tiers) {
            if (kills < tier.requiredKills()) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
