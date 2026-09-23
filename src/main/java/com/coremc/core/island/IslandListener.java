package com.coremc.core.island;

import com.coremc.core.config.MessageService;
import com.coremc.core.world.IslandWorldService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Island border protection and border housekeeping.
 *
 * Inside any island's border only the owner and members may build or
 * interact; the void between islands is wilderness where nobody builds.
 * {@code coremc.island.bypass} (op by default) overrides everything.
 *
 * Deny messages are throttled per player so walking along a border does
 * not spam chat.
 */
public final class IslandListener implements Listener {

    private static final long DENY_COOLDOWN_MILLIS = 2000L;
    private static final String BYPASS_PERMISSION = "coremc.island.bypass";

    private final IslandService islands;
    private final MessageService messages;
    private final IslandWorldService worlds;
    private final Map<UUID, Long> lastDenyMessage = new ConcurrentHashMap<>();

    public IslandListener(final IslandService islands, final MessageService messages,
                          final IslandWorldService worlds) {
        this.islands = islands;
        this.messages = messages;
        this.worlds = worlds;
    }

    // ------------------------------------------------------------------
    // protection
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        if (denyAt(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        if (denyAt(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (block == null || !event.getAction().isRightClick() || !block.getType().isInteractable()) {
            return;
        }
        if (denyAt(event.getPlayer(), block.getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Lava/water dumping inside someone's island (classic grief vector). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        if (denyAt(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        if (denyAt(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(final BlockIgniteEvent event) {
        final Player player = event.getPlayer();
        if (player != null && denyAt(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Item frames / paintings placement. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(final HangingPlaceEvent event) {
        final Player player = event.getPlayer();
        if (player != null && denyAt(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Breaking frames/paintings (including via projectiles). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(final HangingBreakByEntityEvent event) {
        final Player attacker = resolvePlayer(event.getRemover());
        if (attacker != null && denyAt(attacker, event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Killing another island's passive entities (animals, pets, villagers). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(final EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Monster) {
            return; // hostile mobs stay damageable everywhere
        }
        final Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null) {
            return;
        }
        if (denyAt(attacker, event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    private Player resolvePlayer(final org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /**
     * The single protection decision: false = allow, true = deny (with a
     * throttled, branded message explaining why).
     */
    private boolean denyAt(final Player player, final Location location) {
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return false;
        }
        final World islandWorld = worlds.islandWorld();
        if (!islandWorld.equals(location.getWorld())) {
            return false; // protection only applies inside the island world
        }
        final Island island = islands.islandAt(islandWorld, location.getBlockX(), location.getBlockZ());
        if (island == null) {
            deny(player, "island.protected-wilderness", Map.of());
            return true;
        }
        if (island.isMember(player.getUniqueId())) {
            return false;
        }
        deny(player, "island.protected-block", Map.of("owner", island.ownerName()));
        return true;
    }

    private void deny(final Player player, final String key, final Map<String, String> placeholders) {
        final long now = System.currentTimeMillis();
        final Long last = lastDenyMessage.get(player.getUniqueId());
        if (last != null && now - last < DENY_COOLDOWN_MILLIS) {
            return;
        }
        lastDenyMessage.put(player.getUniqueId(), now);
        messages.sendPrefixed(player, key, placeholders);
    }

    // ------------------------------------------------------------------
    // border housekeeping
    // ------------------------------------------------------------------

    /** Re-applies the island border when entering the island world, clears it when leaving. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        final Player player = event.getPlayer();
        if (player.getWorld().equals(worlds.islandWorld())) {
            final Island island = islands.islandOf(player.getUniqueId());
            if (island != null) {
                islands.applyBorder(player, island);
            }
        } else {
            islands.clearBorder(player);
        }
    }

    /** Borders do not survive relogging — re-apply on join. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (player.getWorld().equals(worlds.islandWorld())) {
            final Island island = islands.islandOf(player.getUniqueId());
            if (island != null) {
                islands.applyBorder(player, island);
            }
        }
    }
}
