package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Island model: claim bounds, membership and YAML round-trip. */
class IslandTest {

    private Island sampleIsland() {
        // border 100 -> claim spans (50..150) x (-250..-150) at centre (100, -200)
        return new Island(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "OwnerName",
                "coremc_islands",
                3,
                100,
                64,
                -200,
                100,
                "default",
                1697000000000L,
                100.5,
                68.0,
                -197.5,
                90f,
                10f);
    }

    @Test
    void containsMatchesTheBorderSquare() {
        final Island island = sampleIsland();
        assertTrue(island.contains("coremc_islands", 100, -200), "centre is inside");
        assertTrue(island.contains("coremc_islands", 50, -150), "corner is inside");
        assertTrue(island.contains("coremc_islands", 150, -250), "opposite corner is inside");
        assertFalse(island.contains("coremc_islands", 49, -200), "one block west is outside");
        assertFalse(island.contains("coremc_islands", 151, -200), "one block east is outside");
        assertFalse(island.contains("coremc_islands", 100, -149), "one block north is outside");
    }

    @Test
    void containsOnlyAppliesToTheIslandWorld() {
        final Island island = sampleIsland();
        assertFalse(island.contains("world", 100, -200), "other worlds are never claimed");
    }

    @Test
    void membershipIncludesOwnerButMembersSetDoesNot() {
        final Island island = sampleIsland();
        final UUID member = UUID.fromString("33333333-3333-3333-3333-333333333333");
        assertTrue(island.isMember(island.owner()));
        assertTrue(island.addMember(member));
        assertTrue(island.isMember(member));
        assertFalse(island.members().contains(island.owner()), "owner is not stored as a member");
        assertEquals(1, island.members().size());

        assertFalse(island.addMember(member), "adding twice changes nothing");
        assertEquals(1, island.members().size());

        assertTrue(island.removeMember(member));
        assertFalse(island.isMember(member));
        assertFalse(island.removeMember(member), "removing twice is a no-op");
        assertFalse(island.addMember(island.owner()), "the owner can not be added as a member");
    }

    @Test
    void mapRoundTripPreservesEverything() {
        final Island island = sampleIsland();
        final UUID member = UUID.fromString("33333333-3333-3333-3333-333333333333");
        island.addMember(member);

        final Map<String, Object> map = island.toMap();
        final Island restored = Island.fromMap(map);

        assertEquals(island.id(), restored.id());
        assertEquals(island.owner(), restored.owner());
        assertEquals(island.ownerName(), restored.ownerName());
        assertEquals(island.worldName(), restored.worldName());
        assertEquals(island.slot(), restored.slot());
        assertEquals(island.centerX(), restored.centerX());
        assertEquals(island.baseY(), restored.baseY());
        assertEquals(island.centerZ(), restored.centerZ());
        assertEquals(island.borderSize(), restored.borderSize());
        assertEquals(island.schematic(), restored.schematic());
        assertEquals(island.createdAt(), restored.createdAt());
        assertEquals(island.members(), restored.members());
        assertTrue(restored.isMember(member));

        final Island reSerialized = Island.fromMap(restored.toMap());
        assertEquals(restored.toMap(), reSerialized.toMap(), "round-trip must be stable");
    }

    @Test
    void brokenDataIsRejectedLoudly() {
        final Map<String, Object> map = new java.util.HashMap<>(sampleIsland().toMap());
        map.remove("center");
        assertThrows(IllegalArgumentException.class, () -> Island.fromMap(map));
    }
}
