package com.coremc.core.placeable;

import com.coremc.core.placeable.PlaceableService.Type;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns the stable PDC identity contract for generator and spawner core items. */
final class PlaceableIdentity {

    private final NamespacedKey key;

    PlaceableIdentity(final JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "placeable");
    }

    ItemStack identify(final ItemStack stack, final Type type, final String id) {
        Objects.requireNonNull(stack);
        final ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, type.tag() + ":" + id);
        stack.setItemMeta(meta);
        return stack;
    }

    Optional<Map.Entry<Type, String>> idOf(final ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        final String raw = stack.getItemMeta().getPersistentDataContainer()
                .get(key, PersistentDataType.STRING);
        if (raw == null || raw.length() < 5 || raw.charAt(3) != ':') {
            return Optional.empty();
        }
        final Type type = Type.byTag(raw.substring(0, 3));
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(Map.entry(type, raw.substring(4)));
    }
}
