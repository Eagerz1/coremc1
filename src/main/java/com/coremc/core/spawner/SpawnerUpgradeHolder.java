package com.coremc.core.spawner;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder for the Spawner Upgrade window. Carries the resolved
 * upgrade context (which spawner stack, which mob, target variant,
 * requirements) so the click controller knows what the button means.
 */
public final class SpawnerUpgradeHolder implements InventoryHolder {

    private final SpawnerService.UpgradeContext context;
    private Inventory inventory;

    public SpawnerUpgradeHolder(final SpawnerService.UpgradeContext context) {
        this.context = context;
    }

    public SpawnerService.UpgradeContext context() {
        return context;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(final Inventory inventory) {
        this.inventory = inventory;
    }
}
