package com.coremc.core.role;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents the removed OmniTool overview from being reintroduced into the player journey. */
class OmniToolMenuRoutingTest {

    private static String source(final String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/com/coremc/core/").resolve(relative));
    }

    @Test
    void shiftRightClickRoutesStraightToEnchantMenu() throws IOException {
        final String listener = source("role/OmniToolListener.java");
        assertTrue(listener.contains("new EnchantGui(plugin, profile.roleId())"));
        assertFalse(listener.contains("new OmniToolGui(plugin)"));
    }

    @Test
    void enchantMenuOwnsProgressRoleChangeAndAllUpgrades() throws IOException {
        final String menu = source("enchant/EnchantGui.java");
        assertFalse(menu.contains("import com.coremc.core.role.OmniToolGui"));
        assertTrue(menu.contains("SLOT_ROLE_INFO"));
        assertTrue(menu.contains("SLOT_CHANGE_ROLE"));
        assertTrue(menu.contains("OmniUpgradeCatalog.EFFICIENCY"));
        assertTrue(menu.contains("OmniUpgradeCatalog.FORTUNE"));
        assertTrue(menu.contains("OmniUpgradeCatalog.SMELTER"));
    }

    @Test
    void selectingRoleContinuesIntoEnchantMenu() throws IOException {
        final String selector = source("role/RoleSelectGui.java");
        assertTrue(selector.contains("new EnchantGui(plugin, role.key())"));
    }
}
