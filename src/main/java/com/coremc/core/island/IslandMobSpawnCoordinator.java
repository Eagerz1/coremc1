package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;

/** Owns island natural mob-rate boosts and the configured mob-cap census. */
public final class IslandMobSpawnCoordinator {

    /** islandId -> live natural-mob estimate for the mob-cap track. */
    private final Map<UUID, Integer> mobCounters = new ConcurrentHashMap<>();
    private final CoreMCPlugin plugin;

    public IslandMobSpawnCoordinator(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** Drops the mob-cap counter of a deleted island. */
    public void purgeIsland(final UUID islandId) {
        mobCounters.remove(islandId);
    }

    /** Mob-cap bookkeeping counts every non-player island mob death, any killer or team. */
    public void recordDeath(final LivingEntity victim) {
        final Location at = victim.getLocation();
        if (at.getWorld() != null) {
            plugin.islands().islandAt(at.getWorld().getName(), at.getBlockX(), at.getBlockZ())
                    .ifPresent(island -> mobCounters.computeIfPresent(island.islandId(),
                            (key, count) -> count > 0 ? count - 1 : 0));
        }
    }

    /** Applies the island's natural mob-rate and mob-cap tracks. */
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        final CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL
                && reason != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) {
            return; // spawner/custom/egg spawns belong to their own systems — never capped here
        }
        final Location location = event.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        final var island = plugin.islands().islandAt(
                location.getWorld().getName(), location.getBlockX(), location.getBlockZ());
        if (island.isEmpty()) {
            return;
        }
        final Island value = island.get();
        // The cap only manages islands that bought the track (tier 0 =
        // vanilla behaviour, never a nerf); buying in opts into a
        // raised, enforced cap.
        final int capTier = tierOf(value, "mob-cap");
        final int cap = cfgInt("mob-cap", "base-cap", 10)
                + capTier * cfgInt("mob-cap", "cap-per-level", 5);
        if (capTier > 0) {
            int count = mobCounters.getOrDefault(value.islandId(), 0);
            if (count >= cap) {
                // Self-healing verify scan: chunk unloads/despawns leak the
                // estimate upward, so confirm with a real (rare, bounded)
                // entity census before denying a spawn.
                count = countIslandMobs(value);
                mobCounters.put(value.islandId(), count);
                if (count >= cap) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        mobCounters.merge(value.islandId(), 1, Integer::sum);
        final int rate = tierOf(value, "mob-rate");
        if (rate <= 0
                || !roll(rate * cfgPercent("mob-rate", "chance-percent-per-level", 10) / 100.0)) {
            return;
        }
        if (capTier > 0 && mobCounters.getOrDefault(value.islandId(), 0) >= cap) {
            return; // twins never bypass the cap
        }
        location.getWorld().spawnEntity(location, event.getEntityType());
        mobCounters.merge(value.islandId(), 1, Integer::sum);
    }

    /** Bounded census of living non-player mobs inside an island border. */
    private int countIslandMobs(final Island island) {
        final World world = plugin.getServer().getWorld(island.worldName());
        if (world == null) {
            return 0;
        }
        final int half = Math.max(1, plugin.islands().effectiveBorder(island) / 2);
        final Location center = new Location(world, island.centerX(), 64.0, island.centerZ());
        int count = 0;
        for (final Entity entity : world.getNearbyEntities(center, half, 256, half)) {
            if (entity instanceof LivingEntity && !(entity instanceof Player)
                    && plugin.islands().containsBlock(island,
                            entity.getLocation().getBlockX(), entity.getLocation().getBlockZ())) {
                count++;
            }
        }
        return count;
    }

    private int tierOf(final Island island, final String track) {
        return island.upgrades().getOrDefault(track, 0);
    }

    private int cfgInt(final String track, final String key, final int fallback) {
        return plugin.getConfig().getInt("island.upgrades." + track + "." + key, fallback);
    }

    private int cfgPercent(final String track, final String key, final int fallback) {
        return Math.max(0, cfgInt(track, key, fallback));
    }

    private static boolean roll(final double chance) {
        return chance > 0.0 && ThreadLocalRandom.current().nextDouble() < chance;
    }
}
