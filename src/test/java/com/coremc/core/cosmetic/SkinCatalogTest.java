package com.coremc.core.cosmetic;

import com.coremc.core.role.Role;
import com.coremc.core.util.RawYaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses the REAL bundled skins.yml through {@link SkinCatalog} and locks
 * in the shipped inventory: 30 tool skins (5 collections x 6 roles) + 3
 * hats, model ids 21600-21632, and the season-reset policy semantics.
 */
class SkinCatalogTest {

    private static SkinCatalog bundledCatalog() {
        try {
            final String yaml = Files.readString(Path.of("src/main/resources/skins.yml"));
            final SkinCatalog catalog = SkinCatalog.parse(RawYaml.parseMap(yaml));
            assertTrue(catalog.problems().isEmpty(),
                    "bundled skins.yml must parse without problems: " + catalog.problems());
            return catalog;
        } catch (final java.io.IOException exception) {
            throw new IllegalStateException("bundled skins.yml not readable", exception);
        }
    }

    @Test
    void bundledCatalogHoldsExactlyThirtyToolsAndThreeHats() {
        final SkinCatalog catalog = bundledCatalog();
        assertEquals(33, catalog.all().size(), "total skins");
        assertEquals(30, catalog.all().stream()
                .filter(skin -> skin.type() == SkinType.TOOL).count(), "tool skins");
        assertEquals(3, catalog.hats().size(), "hats");
        assertEquals(5, catalog.collections().size(), "collections");
    }

    @Test
    void everyCollectionCoversEveryRoleOnce() {
        final SkinCatalog catalog = bundledCatalog();
        for (final SkinCollection collection : catalog.collections()) {
            assertEquals(6, collection.toolSkins().size(),
                    collection.id() + " must skin all six roles");
            final Set<Role> roles = new HashSet<>();
            for (final Skin skin : collection.toolSkins()) {
                assertTrue(roles.add(skin.role()),
                        collection.id() + " has a duplicate role: " + skin.role());
            }
            assertEquals(Set.of(Role.values()), roles, collection.id() + " role coverage");
        }
    }

    @Test
    void modelIdsAreTheAllocated21600to21632Range() {
        final SkinCatalog catalog = bundledCatalog();
        final Set<Integer> ids = new HashSet<>();
        for (final Skin skin : catalog.all()) {
            assertTrue(skin.modelId() >= 21600 && skin.modelId() <= 21632,
                    skin.id() + " model id outside 21600-21632: " + skin.modelId());
            assertTrue(ids.add(skin.modelId()), "duplicate model id " + skin.modelId());
        }
        assertEquals(33, ids.size(), "every id in range used exactly once");
        // role binding sanity from the generated catalogue
        assertEquals(21600, catalog.skin("emberforge_miner").orElseThrow().modelId());
        assertEquals(Role.MINER, catalog.skin("emberforge_miner").orElseThrow().role());
        assertEquals(Role.UNIVERSAL, catalog.skin("overgrown_universal").orElseThrow().role());
        assertEquals(21632, catalog.skin("moonlit_cap").orElseThrow().modelId());
        assertEquals(SkinType.HAT, catalog.skin("moonlit_cap").orElseThrow().type());
    }

    @Test
    void toolSkinsFilterByRoleAcrossCollections() {
        final SkinCatalog catalog = bundledCatalog();
        assertEquals(5, catalog.toolSkinsForRole(Role.FISHER).size(),
                "one fisher skin per collection");
        assertEquals("tidecaller", catalog.skin("tidecaller_fisher").orElseThrow().collectionId());
        assertEquals(Material.NETHERITE_PICKAXE, catalog.skin("tidecaller_fisher").orElseThrow().material());
    }

    @Test
    void seasonPolicyDefaultsKeepPurchases() {
        final SkinCatalog catalog = bundledCatalog();
        // default policy 'all': nothing is removed
        assertTrue(catalog.survivesSeasonReset("event"));
        assertTrue(catalog.survivesSeasonReset("store"));
        assertTrue(catalog.survivesSeasonReset("crate:ember"));
    }

    @Test
    void seasonPolicyListRemovesUnlistedSourcesButKeepsProtectedOnes() {
        final SkinCatalog catalog = SkinCatalog.parse(Map.of("skins", Map.of(
                "season-reset-keep-sources", List.of("store"),
                "always-keep-sources", List.of("crate"),
                "collections", Map.of(),
                "hat-skins", Map.of())));
        assertTrue(catalog.survivesSeasonReset("store"));
        assertTrue(catalog.survivesSeasonReset("crate:ember"), "always-keep protects crates");
        assertFalse(catalog.survivesSeasonReset("event"), "event skins reset");
    }

    @Test
    void skinRecordRejectsBadDefinitions() {
        assertThrows(IllegalArgumentException.class,
                () -> new Skin("Bad_ID", SkinType.TOOL, "c", "&f", "", Role.MINER,
                        21600, Material.NETHERITE_PICKAXE, "event"));
        assertThrows(IllegalArgumentException.class,
                () -> new Skin("no_role", SkinType.TOOL, "c", "&f", "", null,
                        21600, Material.NETHERITE_PICKAXE, "event"));
        assertThrows(IllegalArgumentException.class,
                () -> new Skin("hat_with_role", SkinType.HAT, "c", "&f", "", Role.MINER,
                        21600, Material.CARVED_PUMPKIN, "event"));
        assertThrows(IllegalArgumentException.class,
                () -> new Skin("zero_model", SkinType.TOOL, "c", "&f", "", Role.MINER,
                        0, Material.NETHERITE_PICKAXE, "event"));
        final Skin hat = new Skin("ok_hat", SkinType.HAT, "hats", "&fHat", "", null,
                21630, Material.CARVED_PUMPKIN, "store");
        assertFalse(hat.fitsRole(Role.MINER), "hats never fit tool roles");
    }

    @Test
    void malformedEntriesAreSkippedNotFatal() {
        final SkinCatalog catalog = SkinCatalog.parse(Map.of("skins", Map.of(
                "collections", Map.of("broken", Map.of(
                        "display", "&fBroken",
                        "description", "x",
                        "filter-icon", "STONE",
                        "source", "event",
                        "tool-skins", Map.of(
                                "ok_miner", Map.of("role", "miner", "model-id", 21600,
                                        "material", "NETHERITE_PICKAXE"),
                                "bad_role", Map.of("role", "nope", "model-id", 21601,
                                        "material", "NETHERITE_PICKAXE")))),
                "hat-skins", Map.of())));
        assertTrue(catalog.skin("ok_miner").isPresent(), "valid entry survives");
        assertTrue(catalog.skin("bad_role").isEmpty(), "bad entry skipped");
        assertFalse(catalog.problems().isEmpty(), "skips are reported");
    }
}
