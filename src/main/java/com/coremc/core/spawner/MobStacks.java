package com.coremc.core.spawner;

import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Mob stacking: mobs spawned by registered CoreMC spawners merge into
 * single counted entities ("4x Pig") so big spawner farms stay light
 * on entities. Killing a stack drops and credits the whole stack —
 * loot, experience and kill rewards scale with the count — and leaves
 * a replacement stack of count-1 behind, except auto-killed Mythic
 * spawns which die as a whole stack by design.
 *
 * <p>Only mobs from registered spawners stack (a spawn must be within
 * {@link #NEAR_SPAWNER_RANGE} blocks of one); natural and vanilla
 * spawner mobs are untouched.</p>
 */
public final class MobStacks {

    /** A spawn must be this close to a registered spawner of its type. */
    static final int NEAR_SPAWNER_RANGE = 8;

    private final JavaPlugin plugin;
    private final SpawnerConfig config;
    private final SpawnerService spawners;
    private final org.bukkit.NamespacedKey countKey;

    MobStacks(final JavaPlugin plugin, final SpawnerConfig config, final SpawnerService spawners) {
        this.plugin = plugin;
        this.config = config;
        this.spawners = spawners;
        this.countKey = new org.bukkit.NamespacedKey(plugin, "coremc_mobstack");
    }

    /** Stack count of a mob (1 for untagged, vanilla mobs). */
    public int countOf(final LivingEntity entity) {
        final Integer count = entity.getPersistentDataContainer()
                .get(countKey, PersistentDataType.INTEGER);
        return count == null || count < 1 ? 1 : count;
    }

    private void applyCount(final LivingEntity entity, final int count) {
        entity.getPersistentDataContainer().set(countKey, PersistentDataType.INTEGER, count);
        final SpawnerMob mob = config.mobByEntity(entity.getType());
        final String name = mob == null ? entity.getType().name() : mob.name();
        if (count > 1) {
            entity.customName(LegacyComponentSerializer.legacySection().deserialize(
                    ColorUtil.colorize(SpawnerStacks.mobName(name, count))));
            entity.setCustomNameVisible(true);
        } else {
            entity.customName(null);
            entity.setCustomNameVisible(false);
        }
    }

    /**
     * Called for every SPAWNER-reason spawn: tags the new mob as a
     * 1-stack and, when a same-type stack with room is nearby, merges
     * it in. Returns true when the new entity was merged away
     * (removed) — callers must then skip their own per-entity work.
     */
    public boolean tryStack(final LivingEntity spawned) {
        if (!config.mobStackEnabled() || config.mobByEntity(spawned.getType()) == null) {
            return false;
        }
        if (spawners.nearbySpawnerEntry(spawned.getLocation(), spawned.getType(),
                NEAR_SPAWNER_RANGE) == null) {
            return false; // vanilla spawner or random spawn: not ours to stack
        }
        LivingEntity best = null;
        int bestCount = 0;
        for (final Entity nearby : spawned.getNearbyEntities(
                config.mobStackRadius(), config.mobStackRadius(), config.mobStackRadius())) {
            if (!(nearby instanceof LivingEntity living)
                    || living.getType() != spawned.getType()
                    || !living.isValid()) {
                continue;
            }
            final int count = countOf(living);
            if (count > bestCount && count < config.mobStackMax()) {
                best = living;
                bestCount = count;
            }
        }
        if (best != null) {
            applyCount(best, bestCount + 1);
            // the stack adopts the newest member's spawner tier: if a stray
            // (untagged) mob absorbs spawner spawns, the stack must still pay
            // the spawner's kill rate, not Normal
            spawners.applyVariantTag(best, spawners.variantOf(spawned));
            spawned.remove();
            return true;
        }
        applyCount(spawned, 1);
        return false;
    }

    /**
     * Death of a stacked mob: multiplies loot and experience by the
     * stack count and schedules a count-1 replacement. No-op for
     * single (unstacked) mobs and for auto-kill-doomed spawns, which
     * must not leave survivors.
     */
    public void handleDeath(final EntityDeathEvent event, final boolean autoKillPending) {
        final LivingEntity entity = event.getEntity();
        final int count = countOf(entity);
        if (count <= 1) {
            return;
        }
        final List<ItemStack> multiplied = new ArrayList<>();
        for (final ItemStack drop : event.getDrops()) {
            for (final int part : SpawnerStacks.splitAmounts(
                    drop.getAmount() * count, drop.getMaxStackSize())) {
                final ItemStack copy = drop.clone();
                copy.setAmount(part);
                multiplied.add(copy);
            }
        }
        event.getDrops().clear();
        event.getDrops().addAll(multiplied);
        event.setDroppedExp(event.getDroppedExp() * count);

        if (autoKillPending) {
            return; // the whole stack was auto-kill fodder, no replacement
        }
        final EntityType type = entity.getType();
        final Location location = entity.getLocation();
        final World world = entity.getWorld();
        // captured before the entity is discarded: the replacement must pay
        // this stack's variant rate when it is eventually killed
        final SpawnerVariant variant = spawners.variantOf(entity);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            final Chunk chunk = world.getChunkAt(location);
            if (!world.isChunkLoaded(chunk.getX(), chunk.getZ())) {
                return;
            }
            final Entity spawned = world.spawnEntity(location, type,
                    CreatureSpawnEvent.SpawnReason.CUSTOM);
            if (spawned instanceof LivingEntity living) {
                applyCount(living, count - 1);
                spawners.applyVariantTag(living, variant);
            }
        });
    }
}
