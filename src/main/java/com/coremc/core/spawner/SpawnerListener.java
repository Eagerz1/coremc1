package com.coremc.core.spawner;

import com.coremc.core.config.MessageService;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Bridges world events into the spawner system: placing a CoreMC
 * spawner item (sneak-clicking a placed spawner stacks onto it),
 * breaking stacks (one spawner per break, the whole stack when
 * sneaking), explosions, kill rewards, mob stacking and Mythic
 * auto-kill scheduling.
 *
 * Handlers run at HIGHEST (after island protection) and never
 * interfere with vanilla spawners or other plugins' blocks.
 */
public final class SpawnerListener implements Listener {

    private final SpawnerService spawners;
    private final MessageService messages;

    public SpawnerListener(final SpawnerService spawners, final MessageService messages) {
        this.spawners = spawners;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        final ItemStack item = event.getItemInHand();
        final String[] identity = spawners.spawnerItemIdentity(item);
        if (identity == null) {
            return; // not our spawner item
        }
        final Player player = event.getPlayer();

        // A spawner item clicked against a registered spawner stacks onto
        // it instead of placing a new block.
        final SpawnerEntry target = spawners.entryAt(event.getBlockAgainst());
        if (target != null) {
            event.setCancelled(true);
            if (!player.isSneaking()) {
                messages.sendPrefixed(player, "spawner.stack-hint");
                return;
            }
            final SpawnerVariant variant = SpawnerVariant.of(identity[1]);
            if (variant == null) {
                return;
            }
            final int newAmount = spawners.stack(player, target, identity[0], variant,
                    spawners.spawnerItemAmount(item));
            if (newAmount > 0) {
                consumePlacedItem(event);
            }
            return;
        }

        if (!spawners.canPlaceAt(player, event.getBlock())) {
            event.setCancelled(true);
            messages.sendPrefixed(player, "spawner.place-island");
            return;
        }
        final SpawnerVariant variant = SpawnerVariant.of(identity[1]);
        if (variant == null) {
            event.setCancelled(true);
            return;
        }
        spawners.registerPlaced(player, event.getBlock(), identity[0], variant,
                spawners.spawnerItemAmount(item));
    }

    /** Takes one unit of the stacking item back out of the hand that used it. */
    private void consumePlacedItem(final BlockPlaceEvent event) {
        final ItemStack item = event.getItemInHand();
        if (item == null) {
            return;
        }
        final ItemStack reduced = item.getAmount() <= 1
                ? null : item.asQuantity(item.getAmount() - 1);
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            event.getPlayer().getInventory().setItemInOffHand(reduced);
        } else {
            event.getPlayer().getInventory().setItemInMainHand(reduced);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        final SpawnerEntry entry = spawners.entryAt(block);
        if (entry == null) {
            return;
        }
        if (entry.amount() > 1 && !event.getPlayer().isSneaking()) {
            // take a single spawner out; the rest of the stack stays
            event.setCancelled(true);
            spawners.unstackOne(block, entry, event.getPlayer());
            return;
        }
        // sneak-break (or the last spawner of a stack): take them all
        event.setDropItems(false);
        spawners.unstackAll(block, entry, event.getPlayer());
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
                spawners.unstackAll(block, entry, null);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        // mob stacks die as a whole stack: multiplied loot + a count-1
        // replacement (none when the stack was Mythic auto-kill fodder)
        spawners.mobStacks().handleDeath(event, spawners.isAutoKillMob(entity));
        spawners.onMobDeath(entity, event.getEntity().getKiller());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            spawners.onCreatureSpawn(event.getEntity());
        }
    }
}
