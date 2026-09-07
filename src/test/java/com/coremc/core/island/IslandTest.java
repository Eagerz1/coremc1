package com.coremc.core.island;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandTest {

    private final UUID islandId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    private Island fresh() {
        return new Island(islandId, owner, "world", 256, 64, -512, 50, 1_234_567L);
    }

    @Test
    void mapRoundTripPreservesAllFields() {
        final Island island = fresh();
        final UUID member = UUID.randomUUID();
        island.addMember(member);
        island.level(7);
        island.setUpgradeTier("border", 2);

        final Island restored = Island.fromMap(island.toMap());

        assertEquals(island.islandId(), restored.islandId());
        assertEquals(island.owner(), restored.owner());
        assertEquals(island.members(), restored.members());
        assertEquals(island.worldName(), restored.worldName());
        assertEquals(island.centerX(), restored.centerX());
        assertEquals(island.centerY(), restored.centerY());
        assertEquals(island.centerZ(), restored.centerZ());
        assertEquals(island.borderSize(), restored.borderSize());
        assertEquals(7, restored.level());
        assertEquals(2, restored.upgrades().get("border"));
        assertEquals(island.createdMillis(), restored.createdMillis());
    }

    @Test
    void mapRoundTripPreservesThemeAndSettings() {
        final Island island = fresh();
        island.theme("desert");
        island.setting(Island.Setting.VISITORS, true);
        island.setting(Island.Setting.MEMBERS_BUILD, false);

        final Island restored = Island.fromMap(island.toMap());
        assertEquals("desert", restored.theme());
        assertTrue(restored.setting(Island.Setting.VISITORS));
        assertFalse(restored.setting(Island.Setting.MEMBERS_BUILD));
        assertTrue(restored.setting(Island.Setting.MOB_SPAWNING)); // untouched -> default
        assertTrue(restored.setting(Island.Setting.MEMBERS_CONTAINERS));
    }

    @Test
    void legacyMapsGetLegacyDefaultsForThemeAndSettings() {
        final java.util.Map<String, Object> v2 = fresh().toMap();
        v2.remove("theme");
        v2.remove("settings");

        final Island restored = Island.fromMap(v2);
        assertEquals(Island.DEFAULT_THEME, restored.theme());
        assertTrue(restored.setting(Island.Setting.MOB_SPAWNING));
        assertFalse(restored.setting(Island.Setting.VISITORS)); // strict legacy posture
        assertTrue(restored.setting(Island.Setting.MEMBERS_BUILD));
    }

    @Test
    void legacyV1MapGetsDefaults() {
        final java.util.Map<String, Object> v1 = new java.util.LinkedHashMap<>();
        v1.put("island-id", islandId.toString());
        v1.put("owner", owner.toString());
        v1.put("world", "world");
        v1.put("center-x", 0);
        v1.put("center-y", 64);
        v1.put("center-z", 0);
        v1.put("created-millis", 5L);

        final Island restored = Island.fromMap(v1);
        assertEquals(Island.DEFAULT_BORDER_SIZE, restored.borderSize());
        assertEquals(Island.DEFAULT_LEVEL, restored.level());
        assertTrue(restored.members().isEmpty());
        assertTrue(restored.upgrades().isEmpty());
    }

    @Test
    void gridCellUsesFloorDivision() {
        final Island island = new Island(islandId, owner, "world", -128, 64, 300, 50, 0L);
        assertArrayEquals(new int[] {-1, 1}, island.gridCell(256));
    }

    @Test
    void homeIsAboveCentre() {
        final Island island = fresh();
        assertEquals(256.5, island.homeX());
        assertEquals(65.0, island.homeY());
        assertEquals(-511.5, island.homeZ());
    }

    @Test
    void borderSquareContainsExactly50Wide() {
        final Island island = new Island(islandId, owner, "world", 0, 64, 0, 50, 0L);
        // [cx-25, cx+25) x [cz-25, cz+25) — 50 blocks wide, no overlaps with neighbours
        assertTrue(island.containsBlock(-25, -25));
        assertTrue(island.containsBlock(24, 0));
        assertFalse(island.containsBlock(25, 0));
        assertFalse(island.containsBlock(-26, 0));
        assertFalse(island.containsBlock(0, 25));
    }

    @Test
    void membershipRoles() {
        final Island island = fresh();
        final UUID member = UUID.randomUUID();
        assertNull(island.roleOf(member));
        assertTrue(island.addMember(member));
        assertEquals(IslandRole.MEMBER, island.roleOf(member));
        assertEquals(IslandRole.OWNER, island.roleOf(owner));
        assertFalse(island.addMember(member)); // no duplicates
        assertTrue(island.removeMember(member));
        assertNull(island.roleOf(member));
    }
}
