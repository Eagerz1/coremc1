package com.coremc.core.essence;

import com.coremc.core.spawner.SpawnerService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import com.coremc.core.spawner.SpawnerVariant;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Earning rules for the three virtual essences — active gameplay only:
 *
 * <ul>
 *   <li><b>Slayer</b>: manual player kills (melee or the player's own
 *       projectiles). Automated deaths — fall, lava, void, iron
 *       golems, Mythic auto-kill, {@code /kill} — pay nothing. A
 *       killed mob stack pays per mob, at the spawning spawner's
 *       variant rate.</li>
 *   <li><b>Mining</b>: breaking natural resource blocks from the
 *       configured map; player-placed blocks never pay (tracked).</li>
 *   <li><b>Farming</b>: breaking fully grown crops from the
 *       configured map.</li>
 * </ul>
 */
public final class EssenceListener implements Listener {

    private final EssenceManager essences;
    private final EssenceConfig config;
    private final PlacedBlockTracker placedBlocks;
    private final SpawnerService spawners;

    public EssenceListener(final EssenceManager essences, final EssenceConfig config,
                           final PlacedBlockTracker placedBlocks, final SpawnerService spawners) {
        this.essences = essences;
        this.config = config;
        this.placedBlocks = placedBlocks;
        this.spawners = spawners;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        // Only kills a player actively caused pay; getKiller() covers melee
        // and the player's own projectiles and is null for everything else.
        final Player killer = entity.getKiller();
        if (killer == null || entity instanceof Player || entity instanceof ArmorStand) {
            return;
        }
        final boolean activeKill = true;
        // a killed mob stack counts as that many mobs; with the spawner
        // system disabled a lone normal mob still pays its base reward
        final int mobCount = spawners == null ? 1 : Math.max(1, spawners.mobStacks().countOf(entity));
        final SpawnerVariant variant = spawners == null ? SpawnerVariant.NORMAL : spawners.variantOf(entity);
        essences.addKills(killer.getUniqueId(), SlayerRewards.killsFor(activeKill, mobCount));
        essences.give(killer.getUniqueId(), EssenceType.SLAYER,
                SlayerRewards.slayerFor(activeKill, variant, mobCount, config));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(final BlockPlaceEvent event) {
        if (event.isCancelled()) {
            return;
        }
        // only mining-eligible placements are worth remembering
        if (config.miningEligible(event.getBlock().getType())) {
            placedBlocks.add(event.getBlock().getWorld().getName(), event.getBlock().getX(),
                    event.getBlock().getY(), event.getBlock().getZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(final BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        final Block block = event.getBlock();
        final Material material = block.getType();

        // Mining: natural blocks only — a placed block is removed from the
        // tracker and never pays.
        final Long mining = config.miningBlocks().get(material);
        if (mining != null) {
            final String world = block.getWorld().getName();
            if (placedBlocks.isPlayerPlaced(world, block.getX(), block.getY(), block.getZ())) {
                placedBlocks.remove(world, block.getX(), block.getY(), block.getZ());
            } else if (mining > 0) {
                essences.give(event.getPlayer().getUniqueId(), EssenceType.MINING, mining);
            }
        }

        // Farming: crops must be fully grown (Ageable at max age); blocks
        // without an age (melon, pumpkin, sugar cane) always count.
        final Long farming = config.farmingBlocks().get(material);
        if (farming != null && farming > 0 && isFullyGrown(block)) {
            essences.give(event.getPlayer().getUniqueId(), EssenceType.FARMING, farming);
        }
    }

    private static boolean isFullyGrown(final Block block) {
        if (!(block.getBlockData() instanceof Ageable age)) {
            return true;
        }
        return age.getAge() >= age.getMaximumAge();
    }
}
