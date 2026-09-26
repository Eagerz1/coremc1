package com.coremc.core.lootbox;

import com.coremc.core.store.StoreItemTags;
import com.coremc.core.util.GuiItems;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Builds and recognises the physical ENDER_CHEST lootboxes. Identity
 * is the PDC tag only — a renamed vanilla ender chest is never a
 * lootbox, and a real lootbox can never be placed as a block.
 */
public final class LootboxItems {

    private final StoreItemTags tags;

    public LootboxItems(final StoreItemTags tags) {
        this.tags = tags;
    }

    /** Builds {@code amount} physical lootboxes of one type. */
    public ItemStack build(final LootboxDef box, final int amount) {
        final ItemStack stack = GuiItems.glow(GuiItems.item(Material.ENDER_CHEST,
                GuiText.caps(box.name()), itemLore(box)));
        stack.setAmount(Math.max(1, Math.min(64, amount)));
        StoreItemTags.write(stack, tags.lootboxTag(), box.id());
        return stack;
    }

    /** The physical item's lore: categories, 8 + 1 promise, how to open. */
    private static List<String> itemLore(final LootboxDef box) {
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + GuiText.caps("Contains:"));
        for (final String category : box.categories()) {
            lore.add("&8- &7" + GuiText.caps(category));
        }
        lore.add(GuiText.blank());
        lore.add("&f8 " + GuiText.caps("rewards") + " &7+ &d1 "
                + GuiText.caps("guaranteed rare"));
        lore.add(GuiText.blank());
        lore.add(GuiText.click("Right-click a block to open"));
        lore.add("&8" + GuiText.caps("Cannot be placed. CoreMC lootbox"));
        return lore;
    }

    /** The lootbox id carried by an item's PDC — null when not a real lootbox. */
    public String lootboxId(final ItemStack stack) {
        return StoreItemTags.read(stack, tags.lootboxTag());
    }
}
