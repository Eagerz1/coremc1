package com.coremc.core.island;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeCatalogTest {

    @Test
    void everyCategoryHasAtLeastOneTrack() {
        for (final UpgradeCatalog.Category category : UpgradeCatalog.Category.values()) {
            assertTrue(UpgradeCatalog.ofCategory(category).size() >= 1,
                    category + " must have at least one upgrade track");
        }
    }

    @Test
    void theSixSpecCategoriesExistInOrder() {
        final UpgradeCatalog.Category[] order = UpgradeCatalog.Category.values();
        assertEquals(6, order.length);
        assertEquals("MINING", order[0].name());
        assertEquals("FISHING", order[1].name());
        assertEquals("FARMING", order[2].name());
        assertEquals("SLAYING", order[3].name());
        assertEquals("LOGGING", order[4].name());
        assertEquals("ISLAND", order[5].name());
    }

    @Test
    void specTracksArePresent() {
        assertEquals(1, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.MINING).stream()
                .filter(t -> t.id().equals("mining-cube")).count());
        assertEquals(1, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.FARMING).stream()
                .filter(t -> t.id().equals("crop-regrowth")).count());
        assertEquals(4, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.ISLAND).size());
        assertEquals(1, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.ISLAND).stream()
                .filter(t -> t.id().equals("generator-boost")).count());
        assertEquals(1, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.ISLAND).stream()
                .filter(t -> t.id().equals("spawner-boost")).count());
    }

    @Test
    void effectTextsReflectTier() {
        final var crop = UpgradeCatalog.TRACKS.stream()
                .filter(t -> t.id().equals("crop-regrowth")).findFirst().orElseThrow();
        assertTrue(crop.effectText(20).contains("100%")); // spec: level 0-20
        final var cube = UpgradeCatalog.TRACKS.stream()
                .filter(t -> t.id().equals("mining-cube")).findFirst().orElseThrow();
        assertTrue(cube.effectText(4).contains("5x5")); // spec: toward a 5x5 mining area
        assertTrue(cube.effectText(15).contains("5x5")); // spec: 5x5 cap on the 1-15 scale
        final var gen = UpgradeCatalog.TRACKS.stream()
                .filter(t -> t.id().equals("generator-boost")).findFirst().orElseThrow();
        assertTrue(gen.effectText(5).contains("40%"));
        final var spawner = UpgradeCatalog.TRACKS.stream()
                .filter(t -> t.id().equals("spawner-boost")).findFirst().orElseThrow();
        assertTrue(spawner.effectText(5).contains("50%"));
    }
}
