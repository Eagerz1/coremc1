package com.coremc.core.crate;

import com.coremc.core.store.StoreItemTags;
import com.coremc.core.util.GuiItems;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/**
 * Builds and recognises the five physical crate keys. Identity is the
 * PDC tag only — {@link #keyId(ItemStack)} never looks at display
 * names, so renamed tripwire hooks are just tripwire hooks.
 */
public final class KeyItems {

    private final StoreItemTags tags;

    public KeyItems(final StoreItemTags tags) {
        this.tags = tags;
    }

    /** Builds {@code amount} physical keys of one type. */
    public ItemStack build(final KeyDef key, final int amount) {
        final List<String> lore = new ArrayList<>();
        for (final String line : key.description()) {
            lore.add("&7" + GuiText.caps(line));
        }
        if (!lore.isEmpty()) {
            lore.add(GuiText.blank());
        }
        lore.add("&7" + GuiText.caps("Use on the matching crate."));
        lore.add("&8" + GuiText.caps("CoreMC crate key"));
        final ItemStack stack = GuiItems.glow(
                GuiItems.item(key.material(), GuiText.caps(key.name()), lore));
        stack.setAmount(Math.max(1, Math.min(64, amount)));
        StoreItemTags.write(stack, tags.keyTag(), key.id());
        return stack;
    }

    /** The key id carried by an item's PDC — null when it is not a real key. */
    public String keyId(final ItemStack stack) {
        return StoreItemTags.read(stack, tags.keyTag());
    }
}
