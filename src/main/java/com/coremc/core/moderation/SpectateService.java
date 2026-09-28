package com.coremc.core.moderation;

import com.coremc.core.CoreMCPlugin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Handles /spectate sessions and restores all changed player state. */
public final class SpectateService implements Listener {

    private final CoreMCPlugin plugin;
    private final ModerationService moderation;
    private final StaffVisibilityService visibility;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public SpectateService(
            final CoreMCPlugin plugin, final ModerationService moderation, final StaffVisibilityService visibility) {
        this.plugin = plugin;
        this.moderation = moderation;
        this.visibility = visibility;
    }

    public boolean isSpectating(final UUID uuid) {
        return sessions.containsKey(uuid);
    }

    public void startOrSwitch(final Player staff, final Player target) {
        if (staff.equals(target)) {
            moderation.prefixed(staff, "&cYou cannot spectate yourself.");
            return;
        }
        final Session previous = sessions.remove(staff.getUniqueId());
        if (previous != null) {
            restore(staff, previous, false);
        }
        final Session session = new Session(
                staff.getLocation().clone(),
                staff.getGameMode(),
                staff.getAllowFlight(),
                staff.isFlying(),
                visibility.isVanished(staff.getUniqueId()),
                target.getUniqueId(),
                target.getName());
        sessions.put(staff.getUniqueId(), session);
        visibility.setVanished(staff, true, false);
        staff.setGameMode(GameMode.SPECTATOR);
        teleportBehindAndFollow(staff, target);
        moderation.prefixed(staff, "&aNow spectating &f" + target.getName() + "&a. Run &f/spectate&a again to exit.");
    }

    public void exit(final Player staff, final String reason) {
        final Session session = sessions.remove(staff.getUniqueId());
        if (session == null) {
            moderation.prefixed(staff, "&cYou are not spectating anyone.");
            return;
        }
        restore(staff, session, true);
        moderation.prefixed(staff, "&aSpectate ended" + (reason == null || reason.isBlank() ? "." : " &7(" + reason + ")&a."));
    }

    public void restoreAll(final String reason) {
        for (final UUID uuid : java.util.List.copyOf(sessions.keySet())) {
            final Player staff = Bukkit.getPlayer(uuid);
            final Session session = sessions.remove(uuid);
            if (staff != null && session != null) {
                restore(staff, session, false);
                moderation.prefixed(staff, "&eSpectate ended: " + reason + ".");
            }
        }
    }

    private void restore(final Player staff, final Session session, final boolean teleport) {
        try {
            staff.setSpectatorTarget(null);
        } catch (final IllegalStateException ignored) {
            // Not in spectator mode any more.
        }
        if (teleport) {
            staff.teleport(session.location());
        }
        staff.setGameMode(session.gameMode());
        staff.setAllowFlight(session.allowFlight());
        staff.setFlying(session.flying() && session.allowFlight());
        visibility.setVanished(staff, session.wasVanished(), false);
    }

    private void teleportBehindAndFollow(final Player staff, final Player target) {
        final Location behind = target.getLocation().clone().subtract(target.getLocation().getDirection().normalize().multiply(2.0));
        behind.setYaw(target.getLocation().getYaw());
        behind.setPitch(target.getLocation().getPitch());
        staff.teleportAsync(behind).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!staff.isOnline() || !target.isOnline() || !sessions.containsKey(staff.getUniqueId())) {
                return;
            }
            staff.setGameMode(GameMode.SPECTATOR);
            staff.setSpectatorTarget(target);
            visibility.refreshPlayer(staff);
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final Session own = sessions.remove(player.getUniqueId());
        if (own != null) {
            restore(player, own, true);
        }
        for (final Map.Entry<UUID, Session> entry : java.util.List.copyOf(sessions.entrySet())) {
            if (entry.getValue().targetUuid().equals(player.getUniqueId())) {
                final Player staff = Bukkit.getPlayer(entry.getKey());
                if (staff != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> exit(staff, "target disconnected"));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        refreshTargetFollowers(event.getPlayer());
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                final Session session = sessions.get(event.getPlayer().getUniqueId());
                final Player target = session == null ? null : Bukkit.getPlayer(session.targetUuid());
                if (target != null) {
                    event.getPlayer().setSpectatorTarget(target);
                    visibility.refreshPlayer(event.getPlayer());
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        refreshTargetFollowers(event.getPlayer());
    }

    private void refreshTargetFollowers(final Player target) {
        for (final Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (!entry.getValue().targetUuid().equals(target.getUniqueId())) {
                continue;
            }
            final Player staff = Bukkit.getPlayer(entry.getKey());
            if (staff != null) {
                Bukkit.getScheduler().runTask(plugin, () -> teleportBehindAndFollow(staff, target));
            }
        }
    }

    private record Session(
            Location location,
            GameMode gameMode,
            boolean allowFlight,
            boolean flying,
            boolean wasVanished,
            UUID targetUuid,
            String targetName) {
    }
}
