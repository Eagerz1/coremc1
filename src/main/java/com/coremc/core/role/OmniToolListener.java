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

    // ------------------------------------------------------------------ Auto-Smelter upgrade

    /**
     * Auto-Smelter (+ Fortune coating on smelted drops): breaking an ore
     * with the Omni-Tool and the smelter upgrade owned drops the smelted
     * product with furnace-comparable XP. Runs at HIGH+ignoreCancelled so
     * island protection and other deny-plugins always win first.
     * Vanilla drops are suppressed — exactly one smelted stack appears
     * (no double-drop exploits possible).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(final org.bukkit.event.block.BlockBreakEvent event) {
        final ItemStack tool = tools.toolInMainHand(event.getPlayer());
        if (tool == null) {
            return;
        }
        final var profile = plugin.playerData().profileOf(event.getPlayer().getUniqueId()).orElse(null);
        if (profile == null || profile.omniUpgrade(OmniUpgradeCatalog.SMELTER) <= 0) {
            return;
        }
        final var smelted = OmniUpgradeCatalog.smeltedResult(event.getBlock().getType());
        if (smelted == null) {
            return;
        }
        event.setDropItems(false);
        final int amount = 1 + OmniUpgradeCatalog.fortuneRollAmount(
                profile.omniUpgrade(OmniUpgradeCatalog.FORTUNE), java.util.concurrent.ThreadLocalRandom.current());
        event.setExpToDrop(OmniUpgradeCatalog.smeltXpFor(event.getBlock().getType()));
        event.getBlock()
                .getWorld()
                .dropItemNaturally(event.getBlock().getLocation(), new ItemStack(smelted, amount));
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
        // The rule is DESTINATION-based (audit fix): an OmniTool may move
        // anywhere the player could carry it, and may always be TAKEN OUT of
        // a container (retrieving from the ender chest is the supported
        // overflow path); it may never be INSERTED into a foreign container.
        final var top = event.getView().getTopInventory();
        final boolean foreignTop = top != null
                && top.getType() != InventoryType.PLAYER
                && top.getType() != InventoryType.CRAFTING
                && top.getType() != InventoryType.WORKBENCH;
        if (!foreignTop) {
            return; // own inventory / crafting grid — still on the player
        }
        final boolean clickedTop = event.getClickedInventory() == top;
        switch (event.getAction()) {
            case MOVE_TO_OTHER_INVENTORY -> {
                // shift-click: bottom -> container is a stash attempt; container -> bottom is retrieval
                if (!clickedTop && tools.isOmniTool(event.getCurrentItem())) {
                    cancelWithHint(event, player);
                }
            }
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> {
                if (clickedTop && tools.isOmniTool(event.getCursor())) {
                    cancelWithHint(event, player);
                }
            }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (clickedTop) {
                    final ItemStack source = event.getHotbarButton() >= 0
                            ? player.getInventory().getItem(event.getHotbarButton())
                            : player.getInventory().getItemInOffHand(); // F-key swap
                    if (tools.isOmniTool(source)) {
                        cancelWithHint(event, player);
                    }
                }
            }
            default -> {
                // pickups from the container, drops (caught by PlayerDropItemEvent)
                // and collect-to-cursor all end on the player side — allowed.
            }
        }
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

    /**
     * Join re-stamp: a tool that was parked in the ender chest (the
     * supported overflow path) while upgrades were purchased carries stale
     * enchants; refreshHeldTools closes that staleness window on rejoin.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(final org.bukkit.event.player.PlayerJoinEvent event) {
        plugin.playerData().profileOf(event.getPlayer().getUniqueId())
                .ifPresent(profile -> tools.refreshHeldTools(event.getPlayer(), profile));
    }

    /**
     * Death-disconnect safety (audit fix): a player who dies holding the
     * tool and then logs out at the death screen must not leave the tool
     * in the respawn trust until some future respawn — hand it straight
     * back into the (empty-after-death) inventory during the quit event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent event) {
        tools.restoreTrust(event.getPlayer());
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
