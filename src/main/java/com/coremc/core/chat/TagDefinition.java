package com.coremc.core.chat;

import java.util.List;

/**
 * One cosmetic chat tag.
 *
 * The {@code id} is the ONLY stable identity: ownership and selection are
 * persisted by id, never by display name, so re-skinning a tag (display,
 * lore, material, slot) never invalidates player data or paid purchases.
 *
 * @param id           stable lowercase id (grinder, og, beta, ...)
 * @param display      compact '&amp;'-coded chat display, e.g. {@code &8[&cGRINDER&8]}
 * @param menuName     GUI item name (falls back to the display)
 * @param material     Bukkit material name for the GUI icon
 * @param slot         explicit GUI slot, or -1 to auto-place
 * @param permission   permission that also grants the tag ("" = none)
 * @param defaultOwned whether every player owns this tag by default
 * @param lore         extra '&amp;'-coded lore lines shown in the GUI
 */
public record TagDefinition(
        String id,
        String display,
        String menuName,
        String material,
        int slot,
        String permission,
        boolean defaultOwned,
        List<String> lore) {

    public TagDefinition {
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    /** GUI item name, defaulting to the chat display. */
    public String nameOrDisplay() {
        return menuName == null || menuName.isBlank() ? display : menuName;
    }
}
