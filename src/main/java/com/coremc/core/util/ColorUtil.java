package com.coremc.core.util;

import org.bukkit.ChatColor;

/**
 * Standard Minecraft {@code &} colour-code translation (MiniMessage is
 * intentionally not used — plain codes keep messages.yml admin-friendly).
 */
public final class ColorUtil {

    private ColorUtil() {
    }

    public static String colorize(final String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }
}
