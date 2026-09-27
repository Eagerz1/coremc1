package com.coremc.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** progression.yml: 30 island levels, limited Mastery Points, five branches,
 * active source caps and Island Core slot milestones all parse from data. */
class IslandProgressionConfigTest {

    private static IslandProgressionConfig parse(final String yaml) {
        final IslandProgressionConfig config = new IslandProgressionConfig(null);
        final YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.loadFromString(yaml);
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(loaded);
        return config;
    }

    private static IslandProgressionConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/progression.yml"),
                StandardCharsets.UTF_8));
    }

    @Test
    void shippedProgressionHasThirtyLevelSeasonBackbone() throws IOException {
        final IslandProgressionConfig config = bundled();
        assertTrue(config.enabled());
        assertEquals(30, config.maxLevel());
        assertEquals(1, config.levelForXp(0));
        assertEquals(5, config.levelForXp(3600));
        assertEquals(30, config.levelForXp(258000));
        assertEquals("Island Core module slot I", config.levelDef(5).milestone());
        assertEquals("Advanced Industry systems", config.levelDef(20).milestone());
        assertEquals("End-game island prestige", config.levelDef(30).milestone());
    }

    @Test
    void masteryPointsAreLimitedAndCoreSlotsUnlockByLevel() throws IOException {
        final IslandProgressionConfig config = bundled();
        assertEquals(0, config.totalMasteryPoints(1));
        assertEquals(3, config.totalMasteryPoints(5));
        assertTrue(config.totalMasteryPoints(30) < 20,
                "not enough points to buy every branch node at once");
        assertEquals(0, config.moduleSlots(4));
        assertEquals(1, config.moduleSlots(5));
        assertEquals(2, config.moduleSlots(15));
        assertEquals(3, config.moduleSlots(25));
    }

    @Test
    void shippedBranchesExposeNoticeableUnlocks() throws IOException {
        final IslandProgressionConfig config = bundled();
        assertEquals(5, config.branches().size());
        assertNotNull(config.branch("farming").upgrade("crop-mutation"));
        assertNotNull(config.branch("mining").upgrade("mining-surge"));
        assertNotNull(config.branch("fishing").upgrade("sunken-cache"));
        assertNotNull(config.branch("slayer").upgrade("elite-mobs"));
        final IslandProgressionConfig.MasteryUpgrade industry =
                config.branch("industry").upgrade("spawner-logistics");
        assertEquals(Material.HOPPER, industry.icon());
        assertEquals(16, industry.effect("spawner-stack-bonus"), 0.001);
    }

    @Test
    void activeSourcesHaveCapsAndDiminishingReturns() throws IOException {
        final IslandProgressionConfig config = bundled();
        final IslandProgressionConfig.SourceDef farming = config.source("farming");
        assertEquals(3, farming.xpPerUnit(), 0.001);
        assertEquals(1500, farming.softCapXp(), 0.001);
        assertEquals(3000, farming.hardCapXp(), 0.001);
        assertTrue(farming.windowMillis() > 0);
        assertTrue(config.source("milestone").hardCapXp() == 0,
                "one-time milestones are intentionally uncapped");
    }

    @Test
    void brokenRequirementsAreRejected() {
        final String yaml = """
                levels:
                  - {level: 1, xp: 0}
                sources:
                  farming: {name: Farming, icon: WHEAT, xp-per-unit: 1}
                branches:
                  farming:
                    name: Farming
                    icon: WHEAT
                    upgrades:
                      one:
                        name: One
                        icon: WHEAT
                        mastery-points: 1
                        requires: [missing]
                """;
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(yaml));
        assertTrue(error.getMessage().contains("missing"), error.getMessage());
    }

    @Test
    void badMaterialsAreRejected() {
        final String yaml = """
                levels:
                  - {level: 1, xp: 0}
                sources:
                  farming: {name: Farming, icon: NOT_A_BLOCK, xp-per-unit: 1}
                branches:
                  farming:
                    name: Farming
                    icon: WHEAT
                    upgrades:
                      one:
                        name: One
                        icon: WHEAT
                        mastery-points: 1
                """;
        assertThrows(IllegalArgumentException.class, () -> parse(yaml));
    }
}
