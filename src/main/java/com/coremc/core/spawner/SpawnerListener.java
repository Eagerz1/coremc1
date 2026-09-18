package com.coremc.core.spawner;

import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Bridges world events into the spawner system: placing a CoreMC
 * spawner item, breaking/exploding registered spawner blocks, crediting
 * mob deaths, and scheduling Mythic auto-kills for spawner spawns.
 *
 * Handlers run at HIGHEST (after island protection) and never
 * interfere with vanilla spawners or other plugins' blocks.
 */
public final class SpawnerListener implements Listener {

    private final SpawnerService spawners;

    public SpawnerListener(final SpawnerService spawners) {
        this.spawners = spawners;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        final ItemStack item = event.getItemInHand();
        final String[] identity = spawners.spawnerItemIdentity(item);
        if (identity == null) {
            return; // not our spawner item
        }
        final Player player = event.getPlayer();
        if (!spawners.canPlaceAt(player, event.getBlock())) {
            event.setCancelled(true);
            return; // island protection / outside island: let protection messages cover it
        }
        final SpawnerVariant variant = SpawnerVariant.of(identity[1]);
        if (variant == null) {
            event.setCancelled(true);
            return;
        }
        spawners.registerPlaced(player, event.getBlock(), identity[0], variant);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        final SpawnerEntry entry = spawners.entryAt(block);
        if (entry == null) {
            return;
        }
        // Replace the vanilla silk-less drop (nothing) with our spawner item.
        event.setDropItems(false);
        spawners.onSpawnerBreak(block, entry);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        handleExplosion(event.blockList().toArray(new Block[0]));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        handleExplosion(event.blockList().toArray(new Block[0]));
    }

    private void handleExplosion(final Block[] blocks) {
        for (final Block block : blocks) {
            final SpawnerEntry entry = spawners.entryAt(block);
            if (entry != null) {
                spawners.onSpawnerBreak(block, entry);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(final EntityDeathEvent event) {
        spawners.onMobDeath(event.getEntity(), event.getEntity().getKiller());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            spawners.onCreatureSpawn(event.getEntity());
        }
    }
}
