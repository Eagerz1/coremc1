package com.coremc.core.cosmetic;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Wears animated 3D hats as a client-visible overlay.
 *
 * Why an overlay: vanilla Minecraft has NO cosmetic armour slot and no
 * resource-pack way to replace worn armour geometry without replacing the
 * actual item — a "cosmetic overlay" does not exist in the vanilla/Paper
 * architecture. The supported, battle-tested approach (used by cosmetic
 * plugins) is a display entity riding the player's head: we spawn one
 * {@link ItemDisplay} with the {@link ItemDisplay.ItemDisplayTransform#HEAD}
 * transform as a passenger of the player. The player's REAL helmet slot is
 * never touched — helmet attributes and equipped armour are fully
 * preserved, and the hat renders on top of them.
 *
 * Cost profile: exactly one entity per hatted player, spawned once and
 * re-mounted on respawn/world change. The looping animation itself is a
 * client-side texture flipbook — the server never swaps items or spawns
 * entities to animate. A single light task keeps the overlay yaw-locked
 * to the player's head rotation (passengers do not inherit vehicle yaw).
 */
public final class HatOverlayService implements Listener {

    /** Sync cadence for the yaw lock (every tick keeps it smooth and is
     * one metadata update for a single entity per hatted player). */
    private static final long SYNC_PERIOD_TICKS = 1L;

    private final CoreMCPlugin plugin;
    /** player uuid -> overlay entity uuid. */
    private final Map<UUID, UUID> overlays = new ConcurrentHashMap<>();

    public HatOverlayService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** Starts the yaw-sync task (tracked, cancelled on disable). */
    public void start() {
        plugin.tasks().runTimer(this::syncAll, SYNC_PERIOD_TICKS, SYNC_PERIOD_TICKS);
    }

    /** Applies the profile's equipped hat (join / respawn / world change). */
    public void syncWithProfile(final Player player, final PlayerProfile profile) {
        final Skin equipped = plugin.skins().skin(profile.equippedHat())
                .filter(skin -> skin.type() == SkinType.HAT)
                .filter(skin -> profile.ownsSkin(skin.id()))
                .orElse(null);
        if (equipped == null) {
            unequip(player);
            return;
        }
        equip(player, equipped);
    }

    /** Spawns (or replaces) the overlay for {@code player}. */
    public void equip(final Player player, final Skin skin) {
        unequip(player);
        final Location location = player.getLocation();
        final ItemDisplay display = player.getWorld().spawn(location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(plugin.skins().hatStack(skin));
            spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setPersistent(false); // never saved into chunk data; we respawn it
            spawned.setInvulnerable(true);
            spawned.setSilent(true);
            spawned.setBrightness(new Display.Brightness(15, 15));
            final Vector3f translation = new Vector3f();
            final float scale = 1.0f;
            spawned.setTransformation(new org.bukkit.util.Transformation(
                    translation, new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            spawned.setRotation(player.getLocation().getYaw(), 0f);
        });
        overlays.put(player.getUniqueId(), display.getUniqueId());
        player.addPassenger(display);
    }

    /** Removes the overlay if present (idempotent). */
    public void unequip(final Player player) {
        final UUID entityId = overlays.remove(player.getUniqueId());
        if (entityId == null) {
            return;
        }
        final Entity entity = plugin.getServer().getEntity(entityId);
        if (entity != null) {
            entity.remove();
        }
    }

    /** Whether an overlay is currently tracked for the player. */
    public boolean isWearing(final Player player) {
        return overlays.containsKey(player.getUniqueId());
    }

    /** Overlay count (info command). */
    public int activeOverlays() {
        return overlays.size();
    }

    private void syncAll() {
        for (final Map.Entry<UUID, UUID> entry : overlays.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                overlays.remove(entry.getKey());
                continue;
            }
            final Entity entity = plugin.getServer().getEntity(entry.getValue());
            if (entity == null || entity.isDead()) {
                overlays.remove(entry.getKey());
                continue;
            }
            // yaw-lock to the player's head (pitch 0: hats stay level)
            entity.setRotation(player.getLocation().getYaw(), 0f);
            if (entity.getVehicle() != player) {
                // dismounted (dimension change, plugin teleport): re-mount
                player.addPassenger(entity);
                if (entity.getVehicle() != player) {
                    // cannot re-mount: follow directly
                    entity.teleport(player.getLocation());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        // Delay a moment: the player entity is not always mountable at the
        // exact join tick, and hats are re-applied after any world setup.
        plugin.tasks().runLater(() -> {
            final Player player = event.getPlayer();
            if (!player.isOnline()) {
                return;
            }
            plugin.playerData().profileOf(player.getUniqueId())
                    .ifPresent(profile -> syncWithProfile(player, profile));
        }, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        unequip(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(final PlayerRespawnEvent event) {
        remountLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        remountLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(final PlayerTeleportEvent event) {
        remountLater(event.getPlayer());
    }

    /** Overlay entities riding through a dimension change are dropped by
     * the client; re-mount (or respawn) a few ticks after the move. */
    private void remountLater(final Player player) {
        plugin.tasks().runLater(() -> {
            if (!player.isOnline()) {
                return;
            }
            final UUID entityId = overlays.get(player.getUniqueId());
            if (entityId == null) {
                return;
            }
            final Entity entity = plugin.getServer().getEntity(entityId);
            if (entity == null || entity.isDead() || entity.getWorld() != player.getWorld()) {
                // the display did not follow: respawn it from the profile
                plugin.playerData().profileOf(player.getUniqueId())
                        .ifPresent(profile -> syncWithProfile(player, profile));
                return;
            }
            if (entity.getVehicle() != player) {
                player.addPassenger(entity);
            }
        }, 5L);
    }

    /** Removes every overlay (plugin disable). */
    public void shutdown() {
        for (final UUID entityId : overlays.values()) {
            final Entity entity = plugin.getServer().getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
        overlays.clear();
    }
}
