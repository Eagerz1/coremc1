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
 * A mob has one regular {@link SpawnerTier} unlocked by kills of its
 * entity type (counters persist per player). The list shape remains for
 * backwards-compatible loading of old placed spawners.
 */
public record SpawnerDefinition(
        String id,
        String display,
        EntityType entityType,
        Material icon,
        List<SpawnerTier> tiers,
        String unlockKillKey) {

    public SpawnerDefinition {
        tiers = List.copyOf(tiers == null ? List.of() : tiers);
    }

    public SpawnerDefinition(final String id, final String display, final EntityType entityType,
            final Material icon, final List<SpawnerTier> tiers) {
        this(id, display, entityType, icon, tiers, null);
    }

    /** Kill-counter key used in the player profile — lowercase entity name. */
    public String killKey() {
        return entityType.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Wild-mob counter required to unlock this lane. */
    public String requiredKillKey() {
        return unlockKillKey == null || unlockKillKey.isBlank() ? killKey() : unlockKillKey;
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
