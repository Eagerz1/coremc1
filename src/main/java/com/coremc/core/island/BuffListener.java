package com.coremc.core.island;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.block.data.Ageable;

/**
 * Applies the island buffs' world effects: Green Thumb (crops on the
 * island advance extra growth stages per natural grow tick) and XP
 * Surge (mob kills by island players drop multiplied XP).
 */
public final class BuffListener implements Listener {

    /** Crops the growth buff applies to. */
    private static final Set<Material> CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.MELON_STEM, Material.PUMPKIN_STEM,
            Material.SWEET_BERRY_BUSH, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP);

    private final IslandService islands;
    private final IslandBuffService buffs;
    private final IslandUpgradeConfig config;

    public BuffListener(final IslandService islands, final IslandBuffService buffs,
                        final IslandUpgradeConfig config) {
        this.islands = islands;
        this.buffs = buffs;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(final BlockGrowEvent event) {
        if (!config.enabled()) {
            return;
        }
        final IslandUpgradeConfig.BuffDef def = config.buff("crop-growth");
        if (def == null) {
            return;
        }
        final Block block = event.getBlock();
        if (!CROPS.contains(block.getType())
                || !(event.getNewState().getBlockData() instanceof Ageable age)) {
            return;
        }
        final Island island = islands.islandAt(block.getWorld(), block.getX(), block.getZ());
        if (island == null || !buffs.isActive(island, "crop-growth")) {
            return;
        }
        // multiplier 2 -> one extra growth stage per natural grow event
        final int extra = Math.max(0, (int) Math.round(def.multiplier()) - 1);
        final int target = Math.min(age.getAge() + extra, age.getMaximumAge());
        if (target > age.getAge()) {
            age.setAge(target);
            event.getNewState().setBlockData(age);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(final EntityDeathEvent event) {
        if (!config.enabled()) {
            return;
        }
        final IslandUpgradeConfig.BuffDef def = config.buff("xp-boost");
        if (def == null) {
            return;
        }
        final Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        final Island island = islands.islandOf(killer.getUniqueId());
        if (island == null || !buffs.isActive(island, "xp-boost")) {
            return;
        }
        final int xp = event.getDroppedExp();
        if (xp > 0) {
            event.setDroppedExp((int) Math.round(xp * def.multiplier()));
        }
    }
}
