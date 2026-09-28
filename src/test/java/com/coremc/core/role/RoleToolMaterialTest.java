package com.coremc.core.role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Regression: the OmniTool physical form must follow the bound role.
 * Previously every OmniTool was minted as a NETHERITE_PICKAXE, so the
 * Slayer/Fisher/Logger/Farmer tools were the wrong item entirely (and
 * fishers could not even fish, since a pickaxe cannot cast a line — so
 * their role XP, which requires the OmniTool in hand, never accrued).
 */
class RoleToolMaterialTest {

    @Test
    void eachRoleMapsToItsCorrectPhysicalTool() {
        assertEquals(Material.NETHERITE_PICKAXE, Role.MINER.toolMaterial(), "miner mines with a pickaxe");
        assertEquals(Material.NETHERITE_AXE, Role.LOGGER.toolMaterial(), "logger fells with an axe");
        assertEquals(Material.NETHERITE_HOE, Role.FARMER.toolMaterial(), "farmer tends with a hoe");
        assertEquals(Material.NETHERITE_SWORD, Role.SLAYER.toolMaterial(), "slayer fights with a sword");
        assertEquals(Material.FISHING_ROD, Role.FISHER.toolMaterial(), "fisher casts with a rod");
        // Universal keeps the versatile pickaxe form (its historical default).
        assertEquals(Material.NETHERITE_PICKAXE, Role.UNIVERSAL.toolMaterial(),
                "universal keeps a general-purpose pickaxe");
    }

    @Test
    void everyRoleDeclaresANonNullToolMaterial() {
        for (final Role role : Role.values()) {
            assertNotNull(role.toolMaterial(), role.key() + " must declare a tool material");
        }
    }

    @Test
    void slayerToolIsNoLongerAPickaxe() {
        // The originally-reported confirmed bug, pinned so it cannot regress.
        assertEquals(Material.NETHERITE_SWORD, Role.SLAYER.toolMaterial());
    }
}
