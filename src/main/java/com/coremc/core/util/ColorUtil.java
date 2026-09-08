package com.coremc.core.util;

import org.bukkit.ChatColor;

/**
 * Colour helpers for CoreMC messages.
 *
 * CoreMC uses standard Minecraft formatting via '&amp;' colour codes.
 * MiniMessage is intentionally not used.
 */
public final class ColorUtil {

    private ColorUtil() {
    }

    /**
     * Translates '&amp;' colour codes into Bukkit colour codes.
     * Returns an empty string for {@code null} input so messages can
     * never blow up because of a missing config entry.
     */
    public static String colorize(final String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
