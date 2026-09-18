package com.coremc.core.spawner;

import java.util.Map;

/**
 * One priced mob: its entity type, unique drop item, the coin cost of
 * its Normal spawner, extra unlock requirements for buying that
 * spawner (essence + other mobs' drops), and the per-variant upgrade
 * costs in essence / own drops / group relics.
 *
 * @param id           config id (e.g. {@code pig})
 * @param name         display name (e.g. {@code Pig})
 * @param entity       the spawned entity type
 * @param dropMaterial base material of the unique drop item
 * @param dropName     display name of the unique drop item
 * @param spawnerCost  coins a Normal spawner costs
 * @param unlockEssence essence needed to buy the Normal spawner
 * @param unlockDrops  other mobs' drops needed to buy the Normal
 *                     spawner, keyed by mob id (in-group progression)
 * @param upgrades     costs keyed by the variant they unlock
 */
public record SpawnerMob(
        String id,
        String name,
        org.bukkit.entity.EntityType entity,
        org.bukkit.Material dropMaterial,
        String dropName,
        double spawnerCost,
        int unlockEssence,
        Map<String, Integer> unlockDrops,
        Map<SpawnerVariant, SpawnerUpgradeCost> upgrades) {

    public SpawnerMob {
        unlockDrops = Map.copyOf(unlockDrops);
        upgrades = Map.copyOf(upgrades);
    }

    /** Cost to upgrade TO the given variant, or null if unknown. */
    public SpawnerUpgradeCost upgradeCost(final SpawnerVariant variant) {
        return upgrades.get(variant);
    }
}
