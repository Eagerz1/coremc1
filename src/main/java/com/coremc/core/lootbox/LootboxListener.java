package com.coremc.core.lootbox;

import com.coremc.core.config.MessageService;
import com.coremc.core.store.PreviewGui;
import com.coremc.core.store.StoreHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Physical lootbox interaction:
 *
 * <ul>
 *   <li>right-click a block with a lootbox (PDC-validated) → the
 *       opening sequence,</li>
 *   <li>left-click / right-click air → the odds preview,</li>
 *   <li>lootboxes can NEVER be placed as ender chest blocks,</li>
 *   <li>disconnects and world unloads clean the animation up — the
 *       rewards themselves are already pending and safe.</li>
 * </ul>
 */
public final class LootboxListener implements Listener {

    private final LootboxConfig config;
    private final LootboxService service;
    private final LootboxItems items;
    private final PreviewGui previews;
    private final MessageService messages;

    public LootboxListener(final LootboxConfig config, final LootboxService service,
                           final LootboxItems items, final PreviewGui previews,
                           final MessageService messages) {
        this.config = config;
        this.service = service;
        this.items = items;
        this.previews = previews;
        this.messages = messages;
    }

    @EventHandler
    public void onInteract(final PlayerInteractEvent event) {
        final ItemStack held = event.getItem();
        final String boxId = items.lootboxId(held);
        if (boxId == null) {
            return;
        }
        event.setCancelled(true); // a lootbox never acts as a vanilla ender chest
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player player = event.getPlayer();
        final LootboxDef box = config.byId(boxId);
        if (box == null) {
            messages.sendPrefixed(player, "store.lootbox-unknown");
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            service.begin(player, box, player.getInventory().getItemInMainHand(),
                    event.getClickedBlock());
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_AIR) {
            messages.sendPrefixed(player, "store.lootbox-need-block");
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK
                || event.getAction() == Action.LEFT_CLICK_AIR) {
            previews.openLootbox(player, box, StoreHolder.Page.LOOTBOXES);
        }
    }

    /** Lootboxes are items, never blocks — normal placement is always refused. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(final BlockPlaceEvent event) {
        if (items.lootboxId(event.getItemInHand()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        service.onQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldUnload(final WorldUnloadEvent event) {
        service.onWorldUnload(event.getWorld());
    }
}
