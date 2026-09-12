package com.coremc.core.role.xp;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.role.RoleCategory;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;

/** Mining XP: appropriate blocks only (stone-family, ores, deepslate, nether stone). */
public final class MiningXpListener extends RoleXpListener {

    public MiningXpListener(final CoreMCPlugin plugin) {
        super(plugin, RoleCategory.MINING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final int xp = xpFor(event.getBlock().getType());
        if (xp > 0) {
            award(event.getPlayer(), xp);
        }
    }

    static int xpFor(final Material material) {
        return switch (material) {
            case STONE, COBBLESTONE, ANDESITE, DIORITE, GRANITE, TUFF, DEEPSLATE, NETHERRACK, BLACKSTONE, BASALT,
                    CALCITE, SMOOTH_BASALT, END_STONE -> 1;
            case COAL_ORE, DEEPSLATE_COAL_ORE, REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE, NETHER_QUARTZ_ORE -> 3;
            case IRON_ORE, DEEPSLATE_IRON_ORE, LAPIS_ORE, DEEPSLATE_LAPIS_ORE, COPPER_ORE, DEEPSLATE_COPPER_ORE,
                    GOLD_ORE, DEEPSLATE_GOLD_ORE, NETHER_GOLD_ORE -> 5;
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, EMERALD_ORE, DEEPSLATE_EMERALD_ORE, ANCIENT_DEBRIS -> 10;
            default -> 0;
        };
    }
}
