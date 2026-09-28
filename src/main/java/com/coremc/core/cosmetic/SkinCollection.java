package com.coremc.core.cosmetic;

import java.util.List;
import java.util.Objects;
import org.bukkit.Material;

/**
 * A themed skin collection (Emberforge, Riftbound, Astral, Tidecaller,
 * Overgrown): display identity, its filter icon in the /skins GUI and the
 * six tool skins it contains.
 */
public record SkinCollection(
        String id,
        String display,
        String description,
        Material filterIcon,
        String source,
        List<Skin> toolSkins) {

    public SkinCollection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(filterIcon, "filterIcon");
        toolSkins = List.copyOf(toolSkins == null ? List.of() : toolSkins);
    }
}
