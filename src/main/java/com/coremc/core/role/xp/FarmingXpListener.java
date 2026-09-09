package com.coremc.core.role.xp;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.role.RoleCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;

/** Farming XP: harvesting fully grown crops only (no XP for trampling or early breaks). */
public final class FarmingXpListener extends RoleXpListener {

    public FarmingXpListener(final CoreMCPlugin plugin) {
        super(plugin, RoleCategory.FARMING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        if (!(block.getBlockData() instanceof Ageable ageable)) {
            return;
        }
        if (ageable.getAge() >= ageable.getMaximumAge()) {
            award(event.getPlayer(), 3L);
        }
    }
}
