package com.coremc.core.gens;

import com.coremc.core.config.MessageService;
import java.util.Iterator;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Bridges world events into the generator system: placing generator
 * items (sneak-clicking a placed generator stacks onto it), breaking
 * them back into items, right-clicking to open the management window,
 * and every anti-exploit guard that keeps a generator a generator.
 *
 * <p>A placed generator never drops its vanilla block: breaking it
 * returns the CoreMC generator item instead, so an Iron Generator can
 * never be laundered into iron blocks. Explosions, pistons, endermen,
 * fire and block fade all leave generators alone.</p>
 *
 * <p>Handlers run at HIGHEST (after island protection) and ignore any
 * block that is not a registered generator, so vanilla blocks and
 * other plugins are untouched.</p>
 */
public final class GeneratorListener implements Listener {

    private final GeneratorService generators;
    private final GeneratorManageGui manageGui;
    private final MessageService messages;

    public GeneratorListener(final GeneratorService generators,
                             final GeneratorManageGui manageGui,
                             final MessageService messages) {
        this.generators = generators;
        this.manageGui = manageGui;
        this.messages = messages;
    }

    // ------------------------------------------------------------------
    // placing / stacking
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        final ItemStack item = event.getItemInHand();
        final String genId = generators.itemGeneratorId(item);
        if (genId == null) {
            return; // not one of ours
        }
        final Player player = event.getPlayer();

        // A generator item clicked against a placed generator stacks
        // onto it instead of placing a second block.
        final GeneratorEntry target = generators.entryAt(event.getBlockAgainst());
        if (target != null) {
            event.setCancelled(true);
            if (!player.isSneaking()) {
                messages.sendPrefixed(player, "gens.stack-hint");
                return;
            }
            if (generators.stack(player, target, genId, generators.itemAmount(item)) > 0) {
                consumePlacedItem(event);
            }
            return;
        }

        if (!generators.canPlaceAt(player, event.getBlock())) {
            event.setCancelled(true);
            messages.sendPrefixed(player, "gens.place-island");
            return;
        }
        if (!generators.registerPlaced(player, event.getBlock(), genId,
                generators.itemAmount(item))) {
            event.setCancelled(true);
        }
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

    // ------------------------------------------------------------------
    // breaking
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        final GeneratorEntry entry = generators.entryAt(block);
        if (entry == null) {
            return;
        }
        final Player player = event.getPlayer();
        if (!generators.mayManage(player, entry)) {
            event.setCancelled(true);
            messages.sendPrefixed(player, "gens.not-your-generator");
            return;
        }
        // never drop the vanilla block — a generator always comes back
        // as a CoreMC generator item
        event.setDropItems(false);
        event.setExpToDrop(0);
        if (entry.amount() > 1 && !player.isSneaking()) {
            event.setCancelled(true);
            generators.unstackOne(block, entry, player);
            return;
        }
        generators.unstackAll(block, entry, player);
    }

    // ------------------------------------------------------------------
    // the management window
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Block block = event.getClickedBlock();
        final GeneratorEntry entry = generators.entryAt(block);
        if (entry == null) {
            return;
        }
        final Player player = event.getPlayer();
        // sneak-clicking with a generator item is the stacking gesture —
        // let the place event handle it
        if (player.isSneaking()
                && generators.itemGeneratorId(event.getItem()) != null) {
            return;
        }
        event.setCancelled(true);
        if (!generators.mayManage(player, entry)) {
            messages.sendPrefixed(player, "gens.not-your-generator");
            return;
        }
        // holding a generator without sneaking: say how stacking works,
        // then open the window they actually asked for
        if (generators.itemGeneratorId(event.getItem()) != null) {
            messages.sendPrefixed(player, "gens.stack-hint");
        }
        manageGui.open(player, entry);
    }

    // ------------------------------------------------------------------
    // anti-exploit guards
    // ------------------------------------------------------------------

    /** Explosions never destroy (or duplicate) generators. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        protect(event.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        protect(event.blockList().iterator());
    }

    private void protect(final Iterator<Block> blocks) {
        while (blocks.hasNext()) {
            if (generators.entryAt(blocks.next()) != null) {
                blocks.remove();
            }
        }
    }

    /** Pistons cannot move a generator out of its registration. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        for (final Block block : event.getBlocks()) {
            if (generators.entryAt(block) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        for (final Block block : event.getBlocks()) {
            if (generators.entryAt(block) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Endermen (and friends) leave generators alone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
        if (generators.entryAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(final BlockBurnEvent event) {
        if (generators.entryAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFade(final BlockFadeEvent event) {
        if (generators.entryAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }
}
