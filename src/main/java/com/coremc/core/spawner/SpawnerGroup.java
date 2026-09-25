package com.coremc.core.spawner;

import java.util.List;

/**
 * A mob group: one shared rare Relic item and the group's mobs in
 * progression order. (The group's physical Essence item was replaced
 * by the virtual Slayer/Mining/Farming essence system.)
 *
 * @param id            config id (e.g. {@code organic})
 * @param name          display name
 * @param relicMaterial base material of the relic item
 * @param relicName     display name of the relic item
 * @param mobs          the group's mobs, in order
 */
public record SpawnerGroup(
        String id,
        String name,
        org.bukkit.Material relicMaterial,
        String relicName,
        List<SpawnerMob> mobs) {

    public SpawnerGroup {
        mobs = List.copyOf(mobs);
    }

    public SpawnerMob mob(final String mobId) {
        for (final SpawnerMob mob : mobs) {
            if (mob.id().equals(mobId)) {
                return mob;
            }
        }
        return null;
    }
}
