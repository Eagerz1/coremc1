package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Adds CoreMC identity and presentation to mobs from registered spawners.
 * Also upgrades tagged mobs already saved in loaded chunks after a plugin
 * update, and when an old entity is loaded from disk.
 */
public final class SpawnerMobTagger implements Listener {

    private final CoreMCPlugin plugin;
    private final SpawnerTags tags;

    public SpawnerMobTagger(final CoreMCPlugin plugin, final SpawnerTags tags) {
        this.plugin = plugin;
        this.tags = tags;
        // The server's initial chunks are loaded before plugin listeners.
        Bukkit.getScheduler().runTask(plugin, this::refreshLoadedEntities);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        // Find the source CoreMC spawner block within vanilla spawn range.
        final var sourceBlock =
                tags.findSourceBlock(plugin, event.getLocation(), event.getEntityType());
        if (sourceBlock.isEmpty()) {
            return;
        }
        final var placement = plugin.placeables().at(sourceBlock.get().getLocation());
        if (placement.isEmpty()) {
            return;
        }
        final String tierId = placement.get().id();
        if (plugin.spawners().tierFor(tierId).isEmpty()) {
            return;
        }
        tags.tag(event.getEntity(), tierId);
        applySpawnerMobState(event.getEntity());
        // Reapply after the spawn tick in case the server fork rewrites mob attributes.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getEntity().isValid() && !event.getEntity().isDead()) {
                tags.tag(event.getEntity(), tierId);
                applySpawnerMobState(event.getEntity());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void oneHitKill(final EntityDamageByEntityEvent event) {
        if (!tags.isSpawnerBorn(event.getEntity())) {
            return;
        }
        // A sword sweep creates damage events for nearby mobs as well as the
        // one the player clicked. Keep each hit to exactly one spawner mob.
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            event.setCancelled(true);
            return;
        }
        final Entity attacker = event.getDamager();
        final boolean playerAttack = attacker instanceof org.bukkit.entity.Player
                || (attacker instanceof Projectile projectile
                        && projectile.getShooter() instanceof org.bukkit.entity.Player);
        if (playerAttack) {
            event.setDamage(Math.max(event.getDamage(), 2048.0));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventSpawnerMobCombustion(final EntityCombustEvent event) {
        if (tags.isSpawnerBorn(event.getEntity())) {
            event.setCancelled(true);
            event.getEntity().setFireTicks(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(final ChunkLoadEvent event) {
        for (final Entity entity : event.getChunk().getEntities()) {
            refresh(entity);
        }
    }

    private void refreshLoadedEntities() {
        for (final World world : Bukkit.getWorlds()) {
            for (final Entity entity : world.getLivingEntities()) {
                refresh(entity);
            }
        }
    }

    private void refresh(final Entity entity) {
        if (!tags.isSpawnerBorn(entity)) {
            return;
        }
        tags.tierIdOf(entity).ifPresent(tierId -> tags.tag(entity, tierId));
        applySpawnerMobState(entity);
    }

    /** Keeps spawner mobs passive, grounded, pushable, and clearly labelled. */
    private void applySpawnerMobState(final Entity entity) {
        if (entity instanceof Mob mob) {
            mob.setAI(false);
        }
        entity.setGravity(true);
        entity.setCollidable(true);
        entity.setFireTicks(0);
        if (entity instanceof LivingEntity living) {
            living.setCustomName(com.coremc.core.util.ColorUtil.colorize(
                    "&2&l" + prettyEntityName(entity.getType().name()) + " Spawner"));
            living.setCustomNameVisible(true);
        }
    }

    /** ZOMBIFIED_PIGLIN -> "Zombified Piglin" for player-facing names. */
    public static String prettyEntityName(final String enumName) {
        final String[] parts = enumName.toLowerCase(java.util.Locale.ROOT).split("_");
        final StringBuilder out = new StringBuilder();
        for (final String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
