package com.coremc.core.guide;

import com.coremc.core.util.GuiItems;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Shared contextual help icon/component for GUIs that link into /help. */
public final class HelpLinks {

    private HelpLinks() {
    }

    public static ItemStack icon(final String category) {
        final String target = category == null || category.isBlank() ? "help" : category;
        return GuiItems.item(Material.KNOWLEDGE_BOOK, "&b&lʜᴇʟᴘ",
                "&7ᴏᴘᴇɴ ᴛʜᴇ ᴄᴏʀᴇᴍᴄ ɢᴜɪᴅᴇ.",
                "&eClick for /help " + target);
    }
}
