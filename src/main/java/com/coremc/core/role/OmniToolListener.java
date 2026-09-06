package com.coremc.core.role;

import com.coremc.core.CoreMCPlugin;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * OmniTool rules enforcement:
 *  - shift right-click with the tool opens the Omni panel,
 *  - dropping is cancelled (soulbound) with a throttled branded hint,
 *  - moving it into external containers is cancelled (no stashing),
 *  - death strips it from drops and respawn returns it.
 *
 * All counters/throttles are bounded and short-lived.
 */
public final class OmniToolListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MILLIS = 3000L;

    private final CoreMCPlugin plugin;
    private final OmniToolService tools;
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public OmniToolListener(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.tools = plugin.omniTool();
    }

    // ------------------------------------------------------------------ shift-right-click opens the panel

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(final PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || !event.getPlayer().isSneaking()) {
            return;
        }
        if (tools.toolInMainHand(event.getPlayer()) == null) {
            return;
        }
        event.setCancelled(true);
        plugin.gui().open(event.getPlayer(), new OmniToolGui(plugin));
    }

    // ------------------------------------------------------------------ no dropping

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        if (!tools.isOmniTool(event.getItemDrop().getItemStack())) {
            return;
        }
        event.setCancelled(true);
        notifySoulbound(event.getPlayer());
    }

    // ------------------------------------------------------------------ no stashing in containers

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Block any path that would move an OmniTool OUT of the player inventory:
        if (event.getClickedInventory() == null
                || event.getClickedInventory().getType() == InventoryType.PLAYER) {
            return; // moving inside own inventory is fine (and our GUI clicks are cancelled upstream)
        }
        if (tools.isOmniTool(event.getCursor()) || tools.isOmniTool(event.getCurrentItem())) {
            cancelWithHint(event, player);
            return;
        }
        // number-key swap moves the hotbar item into the container
        if (event.getClick() == ClickType.NUMBER_KEY) {
            final ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (tools.isOmniTool(hotbar)) {
                cancelWithHint(event, player);
            }
        }
        // shift-click from a container would pull items OUT of it — allowed;
        // shift-click FROM the player inventory is covered by the PLAYER branch above.
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(final InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!tools.isOmniTool(event.getOldCursor())) {
            return;
        }
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                notifySoulbound(player);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ soulbound across death

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(final PlayerDeathEvent event) {
        tools.onDeath(event.getEntity(), event.getDrops());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRespawn(final PlayerRespawnEvent event) {
        tools.onRespawn(event.getPlayer());
    }

    // ------------------------------------------------------------------

    private void cancelWithHint(final InventoryClickEvent event, final Player player) {
        event.setCancelled(true);
        notifySoulbound(player);
    }

    private void notifySoulbound(final Player player) {
        final long now = System.currentTimeMillis();
        final Long last = lastMessage.get(player.getUniqueId());
        if (last == null || now - last >= MESSAGE_COOLDOWN_MILLIS) {
            lastMessage.put(player.getUniqueId(), now);
            plugin.messages().sendPrefixed(player, "omnitool.soulbound", Map.of());
            if (lastMessage.size() > 256) {
                lastMessage.entrySet().removeIf(e -> now - e.getValue() >= MESSAGE_COOLDOWN_MILLIS);
            }
        }
    }
}
