package com.coremc.core.role.xp;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.role.RoleCategory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;

/** Logging XP: breaking logs/stems/wood only. */
public final class LoggingXpListener extends RoleXpListener {

    public LoggingXpListener(final CoreMCPlugin plugin) {
        super(plugin, RoleCategory.LOGGING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final String name = event.getBlock().getType().name();
        if (name.endsWith("_LOG") || name.endsWith("_STEM") || name.endsWith("_WOOD") || name.endsWith("_HYPHAE")) {
            award(event.getPlayer(), 2L);
        }
    }
}
