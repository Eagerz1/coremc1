package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.util.ColorUtil;
import java.util.Optional;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;

/**
 * The persistent identity tags every CoreMC-spawned mob carries and the
 * lookup that ties a freshly spawned entity back to its spawner block.
 *
 * Tags (PDC on the entity, survive chunk reloads):
 *  - {@code spawner-born}: byte 1 — every mob produced by a CoreMC
 *    spawner (vanilla cycle or boost extras). The economy filters key
 *    off this so spawner farms never feed wild-kill tracks;
 *  - {@code spawner-id}: stable regular-spawner id (for example "zombie-1").
 *
 * The same NamespacedKey instances are shared with the enchant and
 * island listeners (equal namespace+key), so tag checks agree everywhere.
 */
public final class SpawnerTags {

    private final CoreMCPlugin plugin;
    private final NamespacedKey bornKey;
    private final NamespacedKey idKey;

    public SpawnerTags(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.bornKey = new NamespacedKey(plugin, "spawner-born");
        this.idKey = new NamespacedKey(plugin, "spawner-id");
    }

    public NamespacedKey bornKey() {
        return bornKey;
    }

    public NamespacedKey idKey() {
        return idKey;
    }

    /** Vanilla scoreboard tags (visible to /kill selectors, unlike PDC). */
    public static final String VANILLA_SPAWNER_TAG = "coremc.spawner";
    /** Tags {@code entity} as spawner-born with its stable spawner id. */
    public void tag(final Entity entity, final String tierId) {
        final var pdc = entity.getPersistentDataContainer();
        pdc.set(bornKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(idKey, PersistentDataType.STRING, tierId);
        // Vanilla scoreboard tag so admins (and tooling) can select CoreMC
        // mobs with e.g. @e[tag=!coremc.spawner]; the PDC marker is the
        // plugin's source of truth, this mirrors it for commands.
        entity.addScoreboardTag(VANILLA_SPAWNER_TAG);

        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (living instanceof Mob mob) {
            mob.setAI(false);
        }
        living.setGravity(true);
        living.setCollidable(true);
        living.setInvulnerable(false);
        living.setAbsorptionAmount(0.0);
        living.setHealth(1.0);

        // The visible entity nameplate is the mob's hologram. It follows
        // naturally with the entity and remains above its head as it falls
        // or a player pushes it.
        final String display = plugin.spawners().tierFor(tierId)
                .map(ref -> ChatColor.stripColor(ColorUtil.colorize(ref.mob().display())))
                .filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> SpawnerMobTagger.prettyEntityName(entity.getType().name()));
        living.setCustomName(ColorUtil.colorize("&c" + display));
        living.setCustomNameVisible(true);
    }

    public boolean isSpawnerBorn(final Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(bornKey, PersistentDataType.BYTE);
    }

    /** Tier id stamped on the entity, or empty for untagged/foreign spawns. */
    public Optional<String> tierIdOf(final Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    /**
     * Finds the registered CoreMC spawner block responsible for a spawn
     * near {@code origin}. Vanilla scatters cycle mobs within the
     * spawner's spawn range (a few blocks), so the entity's exact location
     * is not the block; we scan the bounded cube for the nearest
     * registered SPAWNER placement whose tier spawns {@code type}.
     */
    public Optional<Block> findSourceBlock(final CoreMCPlugin plugin, final Location origin,
            final org.bukkit.entity.EntityType type) {
        if (origin.getWorld() == null) {
            return Optional.empty();
        }
        final int radius = 6;
        Block best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final Block candidate = origin.getWorld().getBlockAt(
                            origin.getBlockX() + dx, origin.getBlockY() + dy, origin.getBlockZ() + dz);
                    if (candidate.getType() != org.bukkit.Material.SPAWNER) {
                        continue;
                    }
                    final var placement = plugin.placeables().at(candidate.getLocation());
                    if (placement.isEmpty()
                            || placement.get().type() != com.coremc.core.placeable.PlaceableService.Type.SPAWNER) {
                        continue;
                    }
                    final var ref = plugin.spawners().tierFor(placement.get().id());
                    if (ref.isEmpty() || ref.get().mob().entityType() != type) {
                        continue;
                    }
                    final double distance = candidate.getLocation().distanceSquared(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

}
