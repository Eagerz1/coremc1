package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class UpgradeCatalogTest {

    @Test
    void sixCategoriesHold43Tracks() {
        assertEquals(43, UpgradeCatalog.TRACKS.size());
        assertEquals(6, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.MINING).size());
        assertEquals(8, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.FISHING).size());
        assertEquals(8, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.FARMING).size());
        assertEquals(9, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.SLAYING).size());
        assertEquals(8, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.LOGGING).size());
        assertEquals(4, UpgradeCatalog.ofCategory(UpgradeCatalog.Category.ISLAND).size());
    }

    @Test
    void specTracksArePresent() {
        final List<String> expected = List.of(
                "mining-cube", "mining-fortune", "mining-xp", "mining-tokens", "mining-credits",
                "mining-speed",
                "fisher-blessing", "fishing-fortune", "fishing-xp", "fishing-treasure", "fishing-rare",
                "fishing-speed", "fishing-tokens", "fishing-credits",
                "crop-regrowth", "farming-fortune", "farming-xp", "crop-yield", "harvest-speed",
                "seed-efficiency", "farming-tokens", "farming-credits",
                "slayer-force", "slayer-fortune", "slayer-xp", "mob-rate", "mob-cap", "slaying-tokens",
                "slaying-credits", "rare-drops", "boss-damage",
                "woodcutter", "logging-fortune", "logging-xp", "tree-growth", "tree-yield",
                "logging-tokens", "logging-credits", "rare-wood",
                "border", "member-slots", "generator-boost", "spawner-boost");
        assertEquals(43, expected.size());
        for (final String id : expected) {
            assertTrue(UpgradeCatalog.TRACKS.stream().anyMatch(track -> track.id().equals(id)),
                    "missing track " + id);
        }
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
        final var cap = UpgradeCatalog.TRACKS.stream()
                .filter(t -> t.id().equals("mob-cap")).findFirst().orElseThrow();
        assertTrue(cap.effectText(3).contains("25")); // base 10 + 3*5
        final var speed = UpgradeCatalog.TRACKS.stream()
                .filter(t -> t.id().equals("mining-speed")).findFirst().orElseThrow();
        assertTrue(speed.effectText(5).contains("III"));
        assertTrue(speed.effectText(0).contains("none"));
        final var yield = UpgradeCatalog.TRACKS.stream()
                .filter(t -> t.id().equals("crop-yield")).findFirst().orElseThrow();
        assertTrue(yield.effectText(2).contains("+1"));
        assertTrue(yield.effectText(4).contains("+2"));
        final var fishSpeed = UpgradeCatalog.TRACKS.stream()
                .filter(t -> t.id().equals("fishing-speed")).findFirst().orElseThrow();
        assertTrue(fishSpeed.effectText(3).contains("-30%"));
    }
}
