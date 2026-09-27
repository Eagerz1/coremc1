package com.coremc.core.chat;

import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.RawYaml;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cosmetics persist by STABLE ID and survive a save/load round trip
 * (the "restart" contract), plus the idempotent v6 → v7 migration.
 */
class CosmeticPersistenceTest {

    private static final String YAML = """
            tags:
              grinder:
                display: "&8[&cGRINDER&8]"
                name: "&c&lGRINDER"
              og:
                display: "&8[&6OG&8]"
            chat:
              colours:
                light_purple:
                  display: "&dLight Purple"
                  colour: "&d"
              gradients:
                sunset:
                  display: "&cSunset"
                  from: "#ff5555"
                  to: "#ffaa00"
            """;

    private static TagCatalog tags() {
        return TagCatalog.parse(RawYaml.parseMap(YAML), null);
    }

    private static ChatStyleCatalog styles() {
        return ChatStyleCatalog.parse(RawYaml.parseMap(YAML), null);
    }

    private static PlayerProfile roundTrip(final PlayerProfile profile) {
        // Exactly what the YAML store does: dump the map, read it back.
        final Map<String, Object> dumped =
                RawYaml.parseMap(RawYaml.dump(new LinkedHashMap<>(profile.toMap())));
        return PlayerProfile.fromMap(profile.uuid(), dumped);
    }

    @Test
    void tagOwnershipAndSelectionSurviveARestart() {
        final UUID uuid = UUID.randomUUID();
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Tester", 1L);
        CosmeticAccess.grantTag(profile, "grinder");
        CosmeticAccess.grantTag(profile, "og");
        CosmeticAccess.selectTag(profile, tags(), "og", perm -> false);

        final PlayerProfile reloaded = roundTrip(profile);
        assertEquals(uuid, reloaded.uuid());
        assertTrue(reloaded.hasTag("grinder"));
        assertTrue(reloaded.hasTag("og"));
        assertEquals("og", reloaded.equippedTag());
        assertEquals(PlayerProfile.SCHEMA_VERSION, profile.toMap().get("schema-version"));
    }

    @Test
    void chatStyleOwnershipSelectionAndBoldSurviveARestart() {
        final PlayerProfile profile = PlayerProfile.createNew(UUID.randomUUID(), "Tester", 1L);
        CosmeticAccess.grantStyle(profile, "sunset");
        CosmeticAccess.selectStyle(profile, styles(), "sunset", perm -> false);
        profile.chatBold(true);

        final PlayerProfile reloaded = roundTrip(profile);
        assertTrue(reloaded.hasChatStyle("sunset"));
        assertEquals("sunset", reloaded.chatColor());
        assertTrue(reloaded.chatBold());
    }

    @Test
    void renderedOutputIsNeverPersisted() {
        final PlayerProfile profile = PlayerProfile.createNew(UUID.randomUUID(), "Tester", 1L);
        CosmeticAccess.grantStyle(profile, "sunset");
        CosmeticAccess.selectStyle(profile, styles(), "sunset", perm -> false);
        final String dump = RawYaml.dump(new LinkedHashMap<>(profile.toMap()));
        assertFalse(dump.indexOf(ChatRender.SECTION) >= 0, "no rendered §-codes in the profile file");
        assertFalse(dump.contains("#ff5555"), "no rendered gradient colours in the profile file");
        assertTrue(dump.contains("sunset"), "only the stable id is stored");
    }

    @Test
    void legacyVersionSixProfileLoadsWithEmptyStyleOwnership() {
        final Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("schema-version", 6);
        legacy.put("username", "Old");
        legacy.put("owned-tags", List.of("grinder"));
        legacy.put("equipped-tag", "grinder");
        legacy.put("chat-color", "none");
        legacy.put("chat-bold", false);

        final PlayerProfile profile = PlayerProfile.fromMap(UUID.randomUUID(), legacy);
        assertTrue(profile.hasTag("grinder"));
        assertEquals("grinder", profile.equippedTag());
        assertTrue(profile.ownedChatStyles().isEmpty());
        assertEquals("none", profile.chatColor());
        // saving migrates the file forward without touching the values
        assertEquals(PlayerProfile.SCHEMA_VERSION, profile.toMap().get("schema-version"));
        assertEquals(List.of("grinder"), profile.toMap().get("owned-tags"));
    }

    @Test
    void mixedCaseLegacyIdsAreNormalisedNotLost() {
        final Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("username", "Old");
        legacy.put("owned-tags", List.of("GRINDER", "Og"));
        legacy.put("equipped-tag", "GRINDER");
        legacy.put("chat-color", "SUNSET");

        final PlayerProfile profile = PlayerProfile.fromMap(UUID.randomUUID(), legacy);
        assertTrue(profile.hasTag("grinder"));
        assertTrue(profile.hasTag("og"));
        assertEquals("grinder", profile.equippedTag());
        assertEquals("sunset", profile.chatColor());
    }

    @Test
    void migrationMapsLegacyDisplayNamesOntoStableIds() {
        final Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("username", "Old");
        legacy.put("owned-tags", List.of("&8[&cGRINDER&8]"));
        legacy.put("equipped-tag", "&8[&cGRINDER&8]");
        legacy.put("chat-color", "Light Purple");

        final PlayerProfile profile = PlayerProfile.fromMap(UUID.randomUUID(), legacy);
        assertTrue(CosmeticMigration.migrate(profile, tags(), styles()));
        assertTrue(profile.hasTag("grinder"));
        assertEquals("grinder", profile.equippedTag());
        assertEquals("light_purple", profile.chatColor());
    }

    @Test
    void migrationIsIdempotentAndLossless() {
        final PlayerProfile profile = PlayerProfile.createNew(UUID.randomUUID(), "Tester", 1L);
        CosmeticAccess.grantTag(profile, "grinder");
        CosmeticAccess.grantTag(profile, "seasonal-2023"); // id not in the catalogue
        profile.equippedTag("grinder");

        assertFalse(CosmeticMigration.migrate(profile, tags(), styles()), "nothing to migrate");
        assertFalse(CosmeticMigration.migrate(profile, tags(), styles()), "still nothing on a second run");
        assertTrue(profile.hasTag("seasonal-2023"), "unknown ids are never dropped");
        assertEquals("grinder", profile.equippedTag());
    }

    @Test
    void displayNameChangesDoNotAffectStoredIds() {
        final PlayerProfile profile = PlayerProfile.createNew(UUID.randomUUID(), "Tester", 1L);
        CosmeticAccess.grantTag(profile, "grinder");
        CosmeticAccess.selectTag(profile, tags(), "grinder", perm -> false);

        // the same id, restyled in config
        final TagCatalog restyled = TagCatalog.parse(RawYaml.parseMap("""
                tags:
                  grinder:
                    display: "&8{&bTHE GRIND&8}"
                    material: DIAMOND
                """), null);
        assertEquals("grinder", profile.equippedTag());
        assertTrue(CosmeticAccess.ownsTag(profile, restyled.byId("grinder").orElseThrow(), perm -> false));
        assertEquals("&8{&bTHE GRIND&8}",
                CosmeticAccess.renderableTag(profile, restyled, perm -> false).display());
    }
}
