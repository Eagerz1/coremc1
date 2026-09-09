package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Void rescue for the island world: falling past the world's floor
 * teleports the player home (own island) or to the main-world spawn
 * (no island) instead of a void death. No item loss, no death loop.
 *
 * The handler is cheap by construction: players outside the island
 * world, or above the floor, return after two comparisons. A short
 * per-player cooldown guards pathological setups (e.g. a main spawn
 * that is itself over void) against teleport spam.
 */
public final class IslandVoidRescueListener implements Listener {

    /** Blocks below the world floor before the rescue triggers. */
    private static final int FALL_MARGIN = 8;
    /** Minimum gap between two rescues of the same player. */
    private static final long RESCUE_COOLDOWN_MILLIS = 3000L;

    private final CoreMCPlugin plugin;
    private final Map<UUID, Long> lastRescue = new ConcurrentHashMap<>();

    public IslandVoidRescueListener(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        final World world = to.getWorld();
        if (!world.getName().equals(plugin.coreConfig().islandWorldName())) {
            return;
        }
        if (to.getY() >= world.getMinHeight() - FALL_MARGIN) {
            return;
        }
        final Player player = event.getPlayer();
        final long now = System.currentTimeMillis();
        final Long last = lastRescue.get(player.getUniqueId());
        if (last != null && now - last < RESCUE_COOLDOWN_MILLIS) {
            return;
        }
        lastRescue.put(player.getUniqueId(), now);
        if (lastRescue.size() > 512) {
            lastRescue.entrySet().removeIf(entry -> now - entry.getValue() >= RESCUE_COOLDOWN_MILLIS);
        }

        final Location target = plugin.islands().islandOf(player.getUniqueId())
                .map(island -> new Location(world, island.homeX(), island.homeY(), island.homeZ()))
                .orElseGet(() -> plugin.islands().safeSpawn());
        player.setFallDistance(0.0F);
        player.teleport(target);
        player.setFallDistance(0.0F);
        plugin.messages().sendPrefixed(player, "island.rescued", Map.of());
    }
}
