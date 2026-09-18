package com.coremc.core.spawner;

import java.util.List;

/**
 * A mob group: one shared Essence item, one rare Relic item and the
 * group's mobs in progression order.
 *
 * @param id              config id (e.g. {@code organic})
 * @param name            display name
 * @param essenceMaterial base material of the essence item
 * @param essenceName     display name of the essence item
 * @param relicMaterial   base material of the relic item
 * @param relicName       display name of the relic item
 * @param mobs            the group's mobs, in order
 */
public record SpawnerGroup(
        String id,
        String name,
        org.bukkit.Material essenceMaterial,
        String essenceName,
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
