package com.coremc.core.gen;

import com.coremc.core.util.ColorUtil;
import java.util.Locale;
import org.bukkit.Material;

/**
 * Immutable generator type loaded from {@code config.yml generators:<id>}.
 *
 * Generators are placed blocks producing {@link #product()} on right-click
 * after {@link #cooldownSeconds()}. All balance lives in config, never code.
 */
public record GeneratorDefinition(
        String id,
        String display,
        Material blockMaterial,
        Material product,
        long priceCredits,
        long cooldownSeconds) {

    /** Legacy-coloured display name safe for item lore reuse. */
    public String colouredDisplay() {
        return ColorUtil.colorize(display);
    }

    /** Human-readable product name (title-cased vanilla material name). */
    public String productName() {
        final String[] words = product.name().toLowerCase(Locale.ROOT).split("_");
        final StringBuilder out = new StringBuilder();
        for (final String word : words) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
