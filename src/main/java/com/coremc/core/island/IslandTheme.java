package com.coremc.core.island;

import java.util.List;
import org.bukkit.Material;

/**
 * One island theme from {@code themes.yml}.
 *
 * A theme fully describes how a starter island is generated (surface,
 * filler and decoration blocks, optional tree, starter chest contents)
 * and how it is presented in the theme-selection GUI.
 *
 * @param key            config key ("plains")
 * @param display        legacy-coloured display name
 * @param icon           GUI icon material
 * @param description    legacy-coloured lore lines
 * @param top            platform surface material
 * @param under          platform filler material
 * @param tree           whether the generator attempts a tree on placed saplings
 * @param decorations    relative "dx,dy,dz=MATERIAL" entries (dy 0 = surface)
 * @param chestContents  starter chest items "MATERIAL:count"
 */
public record IslandTheme(
        String key,
        String display,
        Material icon,
        List<String> description,
        Material top,
        Material under,
        boolean tree,
        List<String> decorations,
        List<String> chestContents) {
}
