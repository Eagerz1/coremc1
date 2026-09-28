package com.coremc.core.moderation;

import com.coremc.core.CoreMCPlugin;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Runtime enforcement for active mutes, bans and freezes. */
public final class ModerationListener implements Listener {

    private final CoreMCPlugin plugin;
    private final ModerationService moderation;
    private final Map<UUID, Long> lastFreezeReminder = new HashMap<>();

    public ModerationListener(final CoreMCPlugin plugin, final ModerationService moderation) {
        this.plugin = plugin;
        this.moderation = moderation;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        moderation.activeBan(event.getUniqueId()).ifPresent(record ->
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, moderation.banMessage(record)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(final PlayerJoinEvent event) {
        moderation.freeze(event.getPlayer().getUniqueId()).ifPresent(freeze ->
                org.bukkit.Bukkit.getScheduler().runTaskLater(
                        plugin, () -> moderation.sendFrozenMessage(event.getPlayer(), freeze), 20L));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        moderation.handleFrozenDisconnect(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(final io.papermc.paper.event.player.AsyncChatEvent event) {
        moderation.activeMute(event.getPlayer().getUniqueId()).ifPresent(record -> {
            event.setCancelled(true);
            moderation.prefixed(event.getPlayer(), moderation.muteMessage(record));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        final String root = rootCommand(event.getMessage());
        if (root.isBlank()) {
            return;
        }
        final Player player = event.getPlayer();
        if (moderation.isFrozen(player.getUniqueId()) && !moderation.hasCapability(player, "freeze.bypass")) {
            if (!root.equals("freeze") && !moderation.config().isFreezeCommandAllowed(root)) {
                event.setCancelled(true);
                remindFrozen(player);
                return;
            }
        }
        if (moderation.config().isMutedCommandAlias(root)) {
            moderation.activeMute(player.getUniqueId()).ifPresent(record -> {
                event.setCancelled(true);
                moderation.prefixed(player, moderation.muteMessage(record));
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (!moderation.isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        final Location from = event.getFrom();
        final Location to = event.getTo();
        if (to == null || sameBlock(from, to)) {
            return;
        }
        final Location locked = from.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
        remindFrozen(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (!moderation.isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        if (moderation.hasCapability(event.getPlayer(), "freeze.bypass")) {
            return;
        }
        event.setCancelled(true);
        remindFrozen(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (moderation.isFrozen(event.getPlayer().getUniqueId())
                && !moderation.hasCapability(event.getPlayer(), "freeze.bypass")) {
            event.setCancelled(true);
            remindFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        if (moderation.isFrozen(event.getPlayer().getUniqueId())
                && !moderation.hasCapability(event.getPlayer(), "freeze.bypass")) {
            event.setCancelled(true);
            remindFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(final PlayerAttemptPickupItemEvent event) {
        if (moderation.isFrozen(event.getPlayer().getUniqueId())
                && !moderation.hasCapability(event.getPlayer(), "freeze.bypass")) {
            event.setCancelled(true);
            remindFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickup(final EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player
                && moderation.isFrozen(player.getUniqueId())
                && !moderation.hasCapability(player, "freeze.bypass")) {
            event.setCancelled(true);
            remindFrozen(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(final InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && moderation.isFrozen(player.getUniqueId())
                && !moderation.hasCapability(player, "freeze.bypass")) {
            event.setCancelled(true);
            remindFrozen(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (moderation.isFrozen(event.getPlayer().getUniqueId())
                && !moderation.hasCapability(event.getPlayer(), "freeze.bypass")) {
            event.setCancelled(true);
            remindFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (moderation.isFrozen(event.getPlayer().getUniqueId())
                && !moderation.hasCapability(event.getPlayer(), "freeze.bypass")) {
            event.setCancelled(true);
            remindFrozen(event.getPlayer());
        }
    }

    private void remindFrozen(final Player player) {
        final long now = System.currentTimeMillis();
        final long last = lastFreezeReminder.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < 2000L) {
            return;
        }
        lastFreezeReminder.put(player.getUniqueId(), now);
        moderation.freeze(player.getUniqueId()).ifPresent(freeze -> moderation.sendFrozenMessage(player, freeze));
    }

    private static boolean sameBlock(final Location from, final Location to) {
        return from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    private static String rootCommand(final String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        final String trimmed = message.charAt(0) == '/' ? message.substring(1) : message;
        final int space = trimmed.indexOf(' ');
        return ModerationConfig.normaliseCommand(space < 0 ? trimmed : trimmed.substring(0, space));
    }
}
