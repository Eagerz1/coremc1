package com.coremc.core.cosmetic;

import com.coremc.core.CoreMCPlugin;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Applies and removes a visual-only skin without replacing the base item.
 *
 * This service deliberately touches only two cosmetic PDC values and
 * CustomModelData: every gameplay PDC key (placeable identity, companion
 * progression, traits, XP, rarity and abilities) is preserved. When the
 * resource pack is missing the vanilla base material remains a safe fallback.
 */
public final class CosmeticSkinService {

    private final NamespacedKey skinKey;
    private final NamespacedKey baseModelKey;

    public CosmeticSkinService(final CoreMCPlugin plugin) {
        this.skinKey = new NamespacedKey(plugin, "cosmetic-skin");
        this.baseModelKey = new NamespacedKey(plugin, "cosmetic-base-model");
    }

    /** Returns a copy with only the cosmetic model layer changed. */
    public ItemStack apply(final ItemStack base, final CosmeticSkin skin) {
        final ItemStack copy = base.clone();
        final ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            return copy;
        }
        final var pdc = meta.getPersistentDataContainer();
        if (!pdc.has(skinKey, PersistentDataType.STRING) && meta.hasCustomModelData()) {
            pdc.set(baseModelKey, PersistentDataType.INTEGER, meta.getCustomModelData());
        }
        pdc.set(skinKey, PersistentDataType.STRING, skin.id());
        meta.setCustomModelData(skin.modelId());
        copy.setItemMeta(meta);
        return copy;
    }

    /** Removes the cosmetic layer and restores the base model, if one existed. */
    public ItemStack clear(final ItemStack skinned) {
        final ItemStack copy = skinned.clone();
        final ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            return copy;
        }
        final var pdc = meta.getPersistentDataContainer();
        pdc.remove(skinKey);
        final Integer baseModel = pdc.get(baseModelKey, PersistentDataType.INTEGER);
        if (baseModel == null) {
            meta.setCustomModelData(null);
        } else {
            meta.setCustomModelData(baseModel);
            pdc.remove(baseModelKey);
        }
        copy.setItemMeta(meta);
        return copy;
    }

    public Optional<String> skinId(final ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer()
                .get(skinKey, PersistentDataType.STRING));
    }
}
