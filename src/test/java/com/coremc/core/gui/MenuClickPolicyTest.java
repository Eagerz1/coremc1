package com.coremc.core.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

final class MenuClickPolicyTest {

    @Test
    void onlyDeliberateLeftAndRightClicksActivateButtons() {
        assertTrue(MenuClickPolicy.isActionClick(ClickType.LEFT));
        assertTrue(MenuClickPolicy.isActionClick(ClickType.RIGHT));

        assertFalse(MenuClickPolicy.isActionClick(ClickType.SHIFT_LEFT));
        assertFalse(MenuClickPolicy.isActionClick(ClickType.SHIFT_RIGHT));
        assertFalse(MenuClickPolicy.isActionClick(ClickType.NUMBER_KEY));
        assertFalse(MenuClickPolicy.isActionClick(ClickType.DOUBLE_CLICK));
        assertFalse(MenuClickPolicy.isActionClick(ClickType.DROP));
        assertFalse(MenuClickPolicy.isActionClick(ClickType.MIDDLE));
    }
}
