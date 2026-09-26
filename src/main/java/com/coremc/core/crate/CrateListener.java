package com.coremc.core.crate;

import com.coremc.core.store.PreviewGui;
import com.coremc.core.store.StoreHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Physical crate interaction:
 *
 * <ul>
 *   <li>right-click a bound crate with its key (validated by PDC,
 *       never by name) → one opening,</li>
 *   <li>right-click without the key / left-click → the odds
 *       preview,</li>
 *   <li>keys can never be placed as blocks, bound crates can never be
 *       broken (admins sneak-break to unbind).</li>
 * </ul>
 */
public final class CrateListener implements Listener {

    private static final String ADMIN_PERMISSION = "coremc.admin.crates";

    private final CrateService crates;
    private final KeyItems keyItems;
    private final PreviewGui previews;
    private final com.coremc.core.config.MessageService messages;

    public CrateListener(final CrateService crates, final KeyItems keyItems,
                         final PreviewGui previews,
                         final com.coremc.core.config.MessageService messages) {
        this.crates = crates;
        this.keyItems = keyItems;
        this.previews = previews;
        this.messages = messages;
    }

    @EventHandler
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        final CrateDef crate = crates.crateAt(event.getClickedBlock());
        if (crate == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // the off-hand fires a second event for the same click
        }
        final Player player = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            previews.openCrate(player, crate, StoreHolder.Page.KEYS);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final ItemStack held = player.getInventory().getItemInMainHand();
        final String heldKey = keyItems.keyId(held);
        if (heldKey == null) {
            previews.openCrate(player, crate, StoreHolder.Page.KEYS);
            return;
        }
        crates.open(player, crate, held, event.getClickedBlock());
    }

    /** Keys are items, never blocks — placement is always refused. */
    @EventHandler
    public void onPlace(final BlockPlaceEvent event) {
        if (keyItems.keyId(event.getItemInHand()) != null) {
            event.setCancelled(true);
        }
    }

    /** Bound crates cannot be broken; admins sneak-break to unbind. */
    @EventHandler
    public void onBreak(final BlockBreakEvent event) {
        final CrateDef crate = crates.crateAt(event.getBlock());
        if (crate == null) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.isSneaking() && player.hasPermission(ADMIN_PERMISSION)) {
            crates.unbind(event.getBlock().getWorld().getName(), event.getBlock().getX(),
                    event.getBlock().getY(), event.getBlock().getZ());
            messages.sendPrefixed(player, "store.crate-unbound");
            return;
        }
        event.setCancelled(true);
    }
}
