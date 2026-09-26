package com.coremc.core.store;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The PDC identifiers for every custom store item. Keys, lootboxes and
 * reward materials are matched by these tags ONLY — display names are
 * never trusted, so vanilla renaming or crafting can never forge a
 * valid key or lootbox.
 */
public final class StoreItemTags {

    private final NamespacedKey keyTag;
    private final NamespacedKey lootboxTag;
    private final NamespacedKey materialTag;

    public StoreItemTags(final JavaPlugin plugin) {
        this.keyTag = new NamespacedKey(plugin, "coremc_crate_key");
        this.lootboxTag = new NamespacedKey(plugin, "coremc_lootbox");
        this.materialTag = new NamespacedKey(plugin, "coremc_store_material");
    }

    public NamespacedKey keyTag() {
        return keyTag;
    }

    public NamespacedKey lootboxTag() {
        return lootboxTag;
    }

    public NamespacedKey materialTag() {
        return materialTag;
    }

    /** Writes a tag onto an item's meta. */
    public static void write(final ItemStack stack, final NamespacedKey tag, final String value) {
        final ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(tag, PersistentDataType.STRING, value);
            stack.setItemMeta(meta);
        }
    }

    /** Reads a tag off an item (null when absent — i.e. not a CoreMC item). */
    public static String read(final ItemStack stack, final NamespacedKey tag) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        final ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(tag, PersistentDataType.STRING);
    }
}
