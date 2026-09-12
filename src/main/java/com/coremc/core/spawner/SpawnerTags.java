package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;

/**
 * The persistent identity tags every CoreMC-spawned mob carries and the
 * lookup that ties a freshly spawned entity back to its spawner block.
 *
 * Tags (PDC on the entity, survive chunk reloads):
 *  - {@code spawner-born}: byte 1 — every mob produced by a CoreMC
 *    spawner (vanilla cycle or boost extras). The economy filters key
 *    off this so spawner farms never feed wild-kill tracks;
 *  - {@code spawner-id}: purchasable tier id ("zombie-1", "zombie-5"…);
 *  - {@code spawner-ancient}: byte 1 for Ancient variant mobs.
 *
 * The same NamespacedKey instances are shared with the enchant and
 * island listeners (equal namespace+key), so tag checks agree everywhere.
 */
public final class SpawnerTags {

    private final NamespacedKey bornKey;
    private final NamespacedKey idKey;
    private final NamespacedKey ancientKey;

    public SpawnerTags(final CoreMCPlugin plugin) {
        this.bornKey = new NamespacedKey(plugin, "spawner-born");
        this.idKey = new NamespacedKey(plugin, "spawner-id");
        this.ancientKey = new NamespacedKey(plugin, "spawner-ancient");
    }

    public NamespacedKey bornKey() {
        return bornKey;
    }

    public NamespacedKey idKey() {
        return idKey;
    }

    public NamespacedKey ancientKey() {
        return ancientKey;
    }

    /** Vanilla scoreboard tags (visible to /kill selectors, unlike PDC). */
    public static final String VANILLA_SPAWNER_TAG = "coremc.spawner";
    public static final String VANILLA_ANCIENT_TAG = "coremc.ancient";

    /** Tags {@code entity} as spawner-born with tier id + ancient flag. */
    public void tag(final Entity entity, final String tierId, final boolean ancient) {
        final var pdc = entity.getPersistentDataContainer();
        pdc.set(bornKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(idKey, PersistentDataType.STRING, tierId);
        // Vanilla scoreboard tag so admins (and tooling) can select CoreMC
        // mobs with e.g. @e[tag=!coremc.spawner]; the PDC marker is the
        // plugin's source of truth, this mirrors it for commands.
        entity.addScoreboardTag(VANILLA_SPAWNER_TAG);
        if (ancient) {
            pdc.set(ancientKey, PersistentDataType.BYTE, (byte) 1);
            entity.addScoreboardTag(VANILLA_ANCIENT_TAG);
        }
    }

    public boolean isSpawnerBorn(final Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(bornKey, PersistentDataType.BYTE);
    }

    public boolean isAncient(final Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(ancientKey, PersistentDataType.BYTE);
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

    /** Stamps Ancient presentation (visible name, buffed health) onto a living mob. */
    public void decorateAncient(final CoreMCPlugin plugin, final LivingEntity entity, final String mobName) {
        entity.setCustomName(com.coremc.core.util.ColorUtil.colorize(
                plugin.getConfig().getString("spawners.ancient.name-prefix", "&5&lAncient ") + mobName));
        entity.setCustomNameVisible(true);
        entity.setGlowing(true);
        final double multiplier = Math.max(1.0,
                plugin.getConfig().getDouble("spawners.ancient.health-multiplier", 2.0));
        final var healthAttribute = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (healthAttribute != null) {
            final double base = healthAttribute.getBaseValue();
            final double boosted = Math.min(1024.0, base * multiplier);
            healthAttribute.setBaseValue(boosted);
            entity.setHealth(Math.min(boosted, entity.getHealth() + (boosted - base)));
        }
        // Ancients are immovable by knockback: otherwise a fight on a small
        // void island can punch/path them off the edge, where they die with
        // no killer and never pay their Ancient reward.
        final var knockback = entity.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.setBaseValue(1.0);
        }
    }
}
