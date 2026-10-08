package com.coremc.core.gui;

import org.bukkit.event.inventory.ClickType;

/** Clicks which are allowed to activate a CoreMC menu button. */
final class MenuClickPolicy {

    private MenuClickPolicy() {}

    static boolean isActionClick(final ClickType clickType) {
        return clickType == ClickType.LEFT || clickType == ClickType.RIGHT;
    }
}
