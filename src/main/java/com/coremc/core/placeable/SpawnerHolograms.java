package com.coremc.core.placeable;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.util.ColorUtil;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataType;

/** Red, persistent nameplate for each stacked regular spawner. */
public final class SpawnerHolograms {
    private final CoreMCPlugin plugin;
    private final NamespacedKey marker;

    public SpawnerHolograms(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "spawner_hologram");
    }

    public void sync(final Location block, final PlaceableService.Placement placement) {
        remove(block);
        if (block.getWorld() == null || placement == null) return;
        final String name = plugin.spawners().tierFor(placement.id())
                .map(ref -> org.bukkit.ChatColor.stripColor(ColorUtil.colorize(ref.mob().display())))
                .orElse("Spawner");
        final Location at = block.clone().add(0.5, 1.25, 0.5);
        final ArmorStand stand = block.getWorld().spawn(at, ArmorStand.class, entity -> {
            entity.setInvisible(true);
            entity.setMarker(true);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setCustomNameVisible(true);
            entity.setCustomName(ColorUtil.colorize("&c" + name + " Spawner " + placement.stackCount() + "x"));
            entity.getPersistentDataContainer().set(marker, PersistentDataType.STRING,
                    PlaceableService.keyOf(block.getWorld().getName(), block.getBlockX(), block.getBlockY(), block.getBlockZ()));
        });
    }

    public void remove(final Location block) {
        if (block.getWorld() == null) return;
        final Location center = block.clone().add(0.5, 1.25, 0.5);
        for (final ArmorStand stand : block.getWorld().getNearbyEntitiesByType(ArmorStand.class, center, 0.8, 0.8, 0.8)) {
            final String ownerBlock = stand.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
            if (PlaceableService.keyOf(block.getWorld().getName(), block.getBlockX(), block.getBlockY(), block.getBlockZ())
                    .equals(ownerBlock)) stand.remove();
        }
    }
}
