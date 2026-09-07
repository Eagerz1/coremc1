package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Island border protection: inside any island's protected square only
 * the owner and members may build/interact; everyone else is denied
 * with a branded message (throttled to avoid chat spam).
 *
 * {@code coremc.island.bypass} (op by default) overrides protection —
 * for admins and moderators.
 *
 * Registered once in onEnable; holds no per-player state besides the
 * spam-throttle map, which is trimmed opportunistically.
 */
public final class IslandProtectionListener implements Listener {

    private static final long DENY_MESSAGE_COOLDOWN_MILLIS = 2000L;

    private final IslandService islands;
    private final MessageService messages;
    private final Map<UUID, Long> lastDenyMessage = new ConcurrentHashMap<>();

    public IslandProtectionListener(final CoreMCPlugin plugin) {
        this.islands = plugin.islands();
        this.messages = plugin.messages();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (block != null && deny(event.getPlayer(), block)) {
            event.setCancelled(true);
        }
    }

    // ---- audit fixes: grief vectors beyond plain block place/break ----

    /** Lava/water dumping inside a protected island (classic grief vector). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(final org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(final org.bukkit.event.player.PlayerBucketFillEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Item frames / paintings / armor stands placement. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(final org.bukkit.event.hanging.HangingPlaceEvent event) {
        final Player placer = event.getPlayer();
        if (placer != null && deny(placer, event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Breaking frames/armor stands (incl. via projectiles). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(final org.bukkit.event.hanging.HangingBreakByEntityEvent event) {
        final Player attacker = event.getRemover() instanceof Player player
                ? player
                : (event.getRemover() instanceof org.bukkit.entity.Projectile projectile
                        && projectile.getShooter() instanceof Player shooter
                        ? shooter
                        : null);
        if (attacker != null && deny(attacker, event.getEntity().getLocation().getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * Killing another island's passive entities (animals, villagers, pets)
     * or damaging their entities. Hostile mobs stay damageable everywhere —
     * spawner farms are a CoreMC feature.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(final org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Monster) {
            return;
        }
        final Player attacker = event.getDamager() instanceof Player player
                ? player
                : (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                        && projectile.getShooter() instanceof Player shooter
                        ? shooter
                        : null);
        if (attacker != null && deny(attacker, event.getEntity().getLocation().getBlock())) {
            event.setCancelled(true);
        }
    }

    private boolean deny(final Player player, final Block block) {
        if (!islands.isLoaded() || player.hasPermission("coremc.island.bypass")) {
            return false;
        }
        final var island = islands.islandAt(block.getWorld().getName(), block.getX(), block.getZ());
        if (island.isEmpty()) {
            return false; // outside every island — open world rules
        }
        if (island.get().roleOf(player.getUniqueId()) != null) {
            return false; // owner/member
        }
        final long now = System.currentTimeMillis();
        final Long last = lastDenyMessage.get(player.getUniqueId());
        if (last == null || now - last >= DENY_MESSAGE_COOLDOWN_MILLIS) {
            lastDenyMessage.put(player.getUniqueId(), now);
            messages.sendPrefixed(player, "island.protected", Map.of());
            trimThrottle(now);
        }
        return true;
    }

    /** Keeps the throttle map bounded (it can only ever hold recent offenders). */
    private void trimThrottle(final long now) {
        if (lastDenyMessage.size() > 256) {
            lastDenyMessage.entrySet().removeIf(entry -> now - entry.getValue() >= DENY_MESSAGE_COOLDOWN_MILLIS);
        }
    }
}
