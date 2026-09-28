package com.coremc.core.moderation;

import com.coremc.core.CoreMCPlugin;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Staff vanish visibility reconciler. Uses Bukkit hide/show so tab visibility follows viewer permissions. */
public final class StaffVisibilityService implements Listener {

    private final CoreMCPlugin plugin;
    private final ModerationService moderation;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, Boolean> previousCollidable = new ConcurrentHashMap<>();

    public StaffVisibilityService(final CoreMCPlugin plugin, final ModerationService moderation) {
        this.plugin = plugin;
        this.moderation = moderation;
    }

    public boolean isVanished(final UUID uuid) {
        return vanished.contains(uuid);
    }

    public void toggle(final Player player) {
        setVanished(player, !isVanished(player.getUniqueId()), true);
    }

    public void setVanished(final Player player, final boolean vanish, final boolean announce) {
        if (vanish) {
            vanished.add(player.getUniqueId());
            previousCollidable.putIfAbsent(player.getUniqueId(), player.isCollidable());
            player.setCollidable(false);
            for (final Player viewer : Bukkit.getOnlinePlayers()) {
                applyVisibility(viewer, player);
            }
            if (announce) {
                moderation.prefixed(player, "&aVanish enabled. Non-staff can no longer see you in world or tab.");
            }
        } else {
            vanished.remove(player.getUniqueId());
            final Boolean collidable = previousCollidable.remove(player.getUniqueId());
            player.setCollidable(collidable == null || collidable);
            for (final Player viewer : Bukkit.getOnlinePlayers()) {
                viewer.showPlayer(plugin, player);
            }
            if (announce) {
                moderation.prefixed(player, "&cVanish disabled. You are visible again.");
            }
        }
    }

    public void refreshAll() {
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            for (final Player target : Bukkit.getOnlinePlayers()) {
                applyVisibility(viewer, target);
            }
        }
    }

    public void refreshPlayer(final Player player) {
        for (final Player other : Bukkit.getOnlinePlayers()) {
            applyVisibility(player, other); // what this player may see
            applyVisibility(other, player); // who may see this player
        }
    }

    public void restoreAll() {
        for (final Player target : Bukkit.getOnlinePlayers()) {
            final Boolean collidable = previousCollidable.remove(target.getUniqueId());
            if (collidable != null) {
                target.setCollidable(collidable);
            }
        }
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            for (final Player target : Bukkit.getOnlinePlayers()) {
                viewer.showPlayer(plugin, target);
            }
        }
        vanished.clear();
        previousCollidable.clear();
    }

    private void applyVisibility(final Player viewer, final Player target) {
        if (viewer.equals(target)) {
            viewer.showPlayer(plugin, target);
            return;
        }
        if (vanished.contains(target.getUniqueId()) && !moderation.hasCapability(viewer, "vanish.see")) {
            viewer.hidePlayer(plugin, target);
        } else {
            viewer.showPlayer(plugin, target);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(final PlayerJoinEvent event) {
        if (isVanished(event.getPlayer().getUniqueId())) {
            previousCollidable.putIfAbsent(event.getPlayer().getUniqueId(), event.getPlayer().isCollidable());
            event.getPlayer().setCollidable(false);
            event.setJoinMessage(null);
            moderation.prefixed(event.getPlayer(), "&aYou rejoined still vanished.");
        }
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(final PlayerQuitEvent event) {
        if (isVanished(event.getPlayer().getUniqueId())) {
            event.setQuitMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(final PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(final EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player target && vanished.contains(target.getUniqueId())) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Player target
                && vanished.contains(target.getUniqueId())
                && !moderation.hasCapability(event.getPlayer(), "vanish.see")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        final Player target = event.getEntity() instanceof Player player ? player : null;
        final Player damager = playerDamager(event.getDamager());
        if (target != null && vanished.contains(target.getUniqueId())
                && (damager == null || !moderation.hasCapability(damager, "vanish.see"))) {
            event.setCancelled(true);
            return;
        }
        if (damager != null && vanished.contains(damager.getUniqueId())
                && target != null && !moderation.hasCapability(target, "vanish.see")) {
            event.setCancelled(true);
        }
    }

    private Player playerDamager(final Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof org.bukkit.projectiles.ProjectileSource source
                && source instanceof Player player) {
            return player;
        }
        if (entity instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
