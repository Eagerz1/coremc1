package com.coremc.core.player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema v7 persistence for animated skins: ownership and selections
 * round-trip through toMap/fromMap, revocation unequips everywhere, and
 * older (v6) maps load with clean defaults — no skins, nothing equipped,
 * nothing granted.
 */
class PlayerProfileV7Test {

    private static final UUID UUID_A = UUID.nameUUIDFromBytes("skins-a".getBytes());

    @Test
    void newProfilesOwnNoSkins() {
        final PlayerProfile profile = PlayerProfile.createNew(UUID_A, "Tester", 0L);
        assertTrue(profile.ownedSkins().isEmpty(), "nothing is auto-granted");
        assertTrue(profile.equippedToolSkin("miner").isEmpty(), "no equipped skins");
        assertEquals("none", profile.equippedHat(), "no hat equipped");
        assertEquals(7, PlayerProfile.SCHEMA_VERSION);
    }

    @Test
    void skinOwnershipAndSelectionsRoundTrip() {
        final PlayerProfile profile = PlayerProfile.createNew(UUID_A, "Tester", 0L);
        assertTrue(profile.grantSkin("emberforge_miner"));
        assertFalse(profile.grantSkin("emberforge_miner"), "second grant is a no-op");
        profile.grantSkin("ember_crown");
        profile.equipToolSkin("miner", "emberforge_miner");
        profile.equipToolSkin("universal", "astral_universal");
        profile.equipHat("ember_crown");

        final PlayerProfile loaded = PlayerProfile.fromMap(profile.uuid(), profile.toMap());
        assertTrue(loaded.ownsSkin("emberforge_miner"));
        assertTrue(loaded.ownsSkin("ember_crown"));
        assertEquals("emberforge_miner", loaded.equippedToolSkin("miner").orElseThrow());
        assertEquals("astral_universal", loaded.equippedToolSkin("universal").orElseThrow());
        assertEquals("ember_crown", loaded.equippedHat());
        assertEquals(2, loaded.equippedToolSkins().size());
    }

    @Test
    void legacyV6MapLoadsSkinless() {
        final Map<String, Object> v6 = new LinkedHashMap<>();
        v6.put("schema-version", 6);
        v6.put("username", "Old");
        v6.put("role", "miner");
        // no owned-skins / equipped-tool-skins / equipped-hat keys at all
        final PlayerProfile loaded = PlayerProfile.fromMap(UUID_A, v6);
        assertTrue(loaded.ownedSkins().isEmpty());
        assertTrue(loaded.equippedToolSkin("miner").isEmpty());
        assertEquals("none", loaded.equippedHat());
    }

    @Test
    void junkSkinValuesAreSanitisedOnLoad() {
        final Map<String, Object> junk = new LinkedHashMap<>();
        junk.put("username", "Junk");
        junk.put("owned-skins", List.of("  ", "none", "emberforge_miner", "emberforge_miner"));
        junk.put("equipped-tool-skins", Map.of("miner", "none", "fisher", "", "slayer", "tidecaller_slayer"));
        junk.put("equipped-hat", "");
        final PlayerProfile loaded = PlayerProfile.fromMap(UUID_A, junk);
        assertTrue(loaded.ownsSkin("emberforge_miner"));
        assertEquals(1, loaded.ownedSkins().size(), "blank/none/duplicate entries dropped");
        assertTrue(loaded.equippedToolSkin("miner").isEmpty(), "'none' never persists as equipped");
        assertTrue(loaded.equippedToolSkin("fisher").isEmpty());
        assertEquals("tidecaller_slayer", loaded.equippedToolSkin("slayer").orElseThrow());
        assertEquals("none", loaded.equippedHat(), "blank hat becomes none");
    }

    @Test
    void revokeRemovesOwnershipAndUnequipsEverywhere() {
        final PlayerProfile profile = PlayerProfile.createNew(UUID_A, "Tester", 0L);
        profile.grantSkin("ember_crown");
        profile.equipHat("ember_crown");
        assertTrue(profile.revokeSkin("ember_crown"));
        assertEquals("none", profile.equippedHat(), "revoked hat cannot stay equipped");
        assertFalse(profile.ownsSkin("ember_crown"));
        assertFalse(profile.revokeSkin("ember_crown"), "revoking a non-owned skin is a no-op");
    }

    @Test
    void clearingToolSkinSelectionRemovesTheKey() {
        final PlayerProfile profile = PlayerProfile.createNew(UUID_A, "Tester", 0L);
        profile.equipToolSkin("logger", "overgrown_logger");
        profile.equipToolSkin("logger", null);
        assertTrue(profile.equippedToolSkin("logger").isEmpty());
        profile.equipToolSkin("farmer", "none");
        assertTrue(profile.equippedToolSkin("farmer").isEmpty(), "'none' == cleared");
    }
}
