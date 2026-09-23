package com.coremc.core.spawner;

/**
 * What a variant upgrade consumes: group essence, the mob's own
 * unique drops, and (for Mythic) group relics.
 */
public record SpawnerUpgradeCost(int essence, int drops, int relics) {

    public static final SpawnerUpgradeCost ZERO = new SpawnerUpgradeCost(0, 0, 0);

    public boolean isEmpty() {
        return essence <= 0 && drops <= 0 && relics <= 0;
    }
}
