package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Gives every mob produced by a CoreMC spawner block its identity:
 * the {@code spawner-born} marker (other listeners already tag vanilla
 * SPAWNER-reason mobs generically; this handler enriches CoreMC's own),
 * the purchasable tier id, and — for Ancient spawners — the ancient
 * marker plus visible buffed presentation.
 *
 * Runs at NORMAL so the Ancient presentation is in place before any
 * later listener (slots, boosts) inspects the entity. Mobs from
 * non-CoreMC spawner blocks (dungeon spawners, other plugins) only ever
 * receive the generic marker from the slayer handler — never a tier id.
 */
public final class SpawnerMobTagger implements Listener {

    private final CoreMCPlugin plugin;
    private final SpawnerTags tags;

    public SpawnerMobTagger(final CoreMCPlugin plugin, final SpawnerTags tags) {
        this.plugin = plugin;
        this.tags = tags;
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
        final var ref = plugin.spawners().tierFor(tierId);
        if (ref.isEmpty()) {
            return;
        }
        final boolean ancient = ref.get().tier().ancient();
        tags.tag(event.getEntity(), tierId, ancient);
        if (ancient && event.getEntity() instanceof LivingEntity living) {
            tags.decorateAncient(
                    plugin, living, prettyEntityName(ref.get().mob().entityType().name()));
        }
    }

    /** ZOMBIFIED_PIGLIN -> "Zombified Piglin" for ancient names. */
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
