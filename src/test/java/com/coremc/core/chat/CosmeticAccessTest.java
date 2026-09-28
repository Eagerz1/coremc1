package com.coremc.core.chat;

import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.RawYaml;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Grant / revoke / select / locked-selection rules for tags and styles. */
class CosmeticAccessTest {

    private static final Predicate<String> NO_PERMS = perm -> false;

    private static final String YAML = """
            tags:
              grinder:
                display: "&8[&cGRINDER&8]"
              og:
                display: "&8[&6OG&8]"
              starter:
                display: "&8[&7STARTER&8]"
                default-owned: true
            chat:
              colours:
                red:
                  display: "&cRed"
                  colour: "&c"
                white:
                  display: "&fWhite"
                  colour: "&f"
                  default-owned: true
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

    private static PlayerProfile profile() {
        return PlayerProfile.createNew(UUID.randomUUID(), "Tester", 1L);
    }

    @Test
    void lockedTagCannotBeSelected() {
        final PlayerProfile profile = profile();
        assertEquals(CosmeticAccess.Result.LOCKED,
                CosmeticAccess.selectTag(profile, tags(), "grinder", NO_PERMS));
        assertEquals("none", profile.equippedTag());
    }

    @Test
    void grantThenSelectThenClear() {
        final PlayerProfile profile = profile();
        assertTrue(CosmeticAccess.grantTag(profile, "grinder"));
        assertFalse(CosmeticAccess.grantTag(profile, "grinder"), "granting twice is a no-op");
        assertEquals(CosmeticAccess.Result.SELECTED,
                CosmeticAccess.selectTag(profile, tags(), "grinder", NO_PERMS));
        assertEquals("grinder", profile.equippedTag());
        assertEquals(CosmeticAccess.Result.UNCHANGED,
                CosmeticAccess.selectTag(profile, tags(), "grinder", NO_PERMS));
        assertTrue(CosmeticAccess.clearTag(profile));
        assertEquals("none", profile.equippedTag());
        assertFalse(CosmeticAccess.clearTag(profile));
        assertTrue(profile.hasTag("grinder"), "clearing the selection never revokes ownership");
    }

    @Test
    void revokeUnequipsAndRemovesOwnership() {
        final PlayerProfile profile = profile();
        CosmeticAccess.grantTag(profile, "grinder");
        CosmeticAccess.selectTag(profile, tags(), "grinder", NO_PERMS);
        final TagDefinition grinder = tags().byId("grinder").orElseThrow();
        assertTrue(CosmeticAccess.revokeTag(profile, grinder, NO_PERMS));
        assertFalse(profile.hasTag("grinder"));
        assertEquals("none", profile.equippedTag());
        assertFalse(CosmeticAccess.revokeTag(profile, grinder, NO_PERMS));
    }

    @Test
    void permissionGrantsOwnershipWithoutProfileEntry() {
        final PlayerProfile profile = profile();
        final Predicate<String> perms = perm -> perm.equals("coremc.tag.og");
        assertEquals(CosmeticAccess.Result.SELECTED,
                CosmeticAccess.selectTag(profile, tags(), "og", perms));
        assertFalse(profile.hasTag("og"), "permission ownership is not persisted as a grant");
        assertNotNull(CosmeticAccess.renderableTag(profile, tags(), perms));
        // permission taken away -> tag stops rendering, selection is inert
        assertNull(CosmeticAccess.renderableTag(profile, tags(), NO_PERMS));
    }

    @Test
    void wildcardPermissionGrantsEveryTag() {
        final PlayerProfile profile = profile();
        final Predicate<String> perms = perm -> perm.equals(TagCatalog.PERMISSION_WILDCARD);
        for (final TagDefinition tag : tags().all()) {
            assertTrue(CosmeticAccess.ownsTag(profile, tag, perms), tag.id());
        }
    }

    @Test
    void defaultOwnedTagsNeedNoGrant() {
        final PlayerProfile profile = profile();
        assertEquals(CosmeticAccess.Result.SELECTED,
                CosmeticAccess.selectTag(profile, tags(), "starter", NO_PERMS));
    }

    @Test
    void unknownTagIsRejectedAndNoneClears() {
        final PlayerProfile profile = profile();
        assertEquals(CosmeticAccess.Result.UNKNOWN,
                CosmeticAccess.selectTag(profile, tags(), "does-not-exist", NO_PERMS));
        CosmeticAccess.grantTag(profile, "grinder");
        CosmeticAccess.selectTag(profile, tags(), "grinder", NO_PERMS);
        assertEquals(CosmeticAccess.Result.CLEARED,
                CosmeticAccess.selectTag(profile, tags(), "none", NO_PERMS));
    }

    @Test
    void deletedTagNeverRendersButOwnershipSurvives() {
        final PlayerProfile profile = profile();
        CosmeticAccess.grantTag(profile, "seasonal");
        profile.equippedTag("seasonal");
        assertNull(CosmeticAccess.renderableTag(profile, tags(), NO_PERMS));
        assertTrue(profile.ownedTags().contains("seasonal"));
    }

    @Test
    void chatStyleGrantSelectRevokeAndReset() {
        final PlayerProfile profile = profile();
        assertEquals(CosmeticAccess.Result.LOCKED,
                CosmeticAccess.selectStyle(profile, styles(), "sunset", NO_PERMS));
        assertTrue(CosmeticAccess.grantStyle(profile, "sunset"));
        assertEquals(CosmeticAccess.Result.SELECTED,
                CosmeticAccess.selectStyle(profile, styles(), "sunset", NO_PERMS));
        assertEquals("sunset", profile.chatColor());
        profile.chatBold(true);
        assertTrue(CosmeticAccess.resetStyle(profile));
        assertEquals("none", profile.chatColor());
        assertFalse(profile.chatBold());
        assertFalse(CosmeticAccess.resetStyle(profile));
        assertTrue(profile.hasChatStyle("sunset"), "reset keeps ownership");

        final ChatStyle sunset = styles().byId("sunset").orElseThrow();
        CosmeticAccess.selectStyle(profile, styles(), "sunset", NO_PERMS);
        assertTrue(CosmeticAccess.revokeStyle(profile, sunset, NO_PERMS));
        assertFalse(profile.hasChatStyle("sunset"));
        assertEquals("none", profile.chatColor());
    }

    @Test
    void defaultOwnedStyleIsUsableAndRenders() {
        final PlayerProfile profile = profile();
        assertEquals(CosmeticAccess.Result.SELECTED,
                CosmeticAccess.selectStyle(profile, styles(), "white", NO_PERMS));
        final ChatStyle style = CosmeticAccess.renderableStyle(profile, styles(), NO_PERMS);
        assertNotNull(style);
        assertEquals(ChatRender.SECTION + "fhi", style.render("hi", false, 64));
    }

    @Test
    void boldIsPermissionGatedUnlessFree() {
        assertFalse(CosmeticAccess.boldAllowed("coremc.chatcolour.bold", false, NO_PERMS));
        assertTrue(CosmeticAccess.boldAllowed("coremc.chatcolour.bold", true, NO_PERMS));
        assertTrue(CosmeticAccess.boldAllowed("coremc.chatcolour.bold", false,
                perm -> perm.equals("coremc.chatcolour.bold")));
    }

    @Test
    void catalogueParsesSolidsAndGradientsSeparately() {
        final ChatStyleCatalog catalog = styles();
        assertEquals(List.of("red", "white"), catalog.solids().stream().map(ChatStyle::id).toList());
        assertEquals(List.of("sunset"), catalog.gradients().stream().map(ChatStyle::id).toList());
        assertEquals(Set.of("red", "white", "sunset"), Set.copyOf(catalog.ids()));
    }
}
