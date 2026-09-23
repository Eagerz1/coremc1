package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * upgrades.yml parsing: the shipped defaults must load, the lookups
 * must be exact, broken files must throw (and never half-work), and
 * the disabled fallback must leave members uncapped.
 */
class IslandUpgradeConfigTest {

    private static final String DEFAULTS = String.join("\n",
            "upgrades:",
            "  claim-size:",
            "    name: \"Island Expansion\"",
            "    icon: GRASS_BLOCK",
            "    growth-per-level: 10",
            "    steps: [2500, 10000, 30000]",
            "  member-slots:",
            "    name: \"Member Slots\"",
            "    icon: PLAYER_HEAD",
            "    base-slots: 3",
            "    slots-per-level: 1",
            "    steps: [2000, 8000, 25000]",
            "buffs:",
            "  crop-growth:",
            "    name: \"Green Thumb\"",
            "    icon: WHEAT",
            "    duration-minutes: 30",
            "    price: 1500",
            "    multiplier: 2",
            "  spawner-boost:",
            "    name: \"Spawner Overdrive\"",
            "    icon: SPAWNER",
            "    duration-minutes: 30",
            "    price: 2500",
            "    multiplier: 2",
            "  xp-boost:",
            "    name: \"XP Surge\"",
            "    icon: EXPERIENCE_BOTTLE",
            "    duration-minutes: 30",
            "    price: 1000",
            "    multiplier: 2");

    /** Parses the shipped src/main/resources/upgrades.yml (the real defaults). */
    @Test
    void shippedDefaultsParseCleanly(@TempDir final Path tempDir) throws Exception {
        final String yaml = java.nio.file.Files.readString(
                Path.of("src/main/resources/upgrades.yml"));
        final IslandUpgradeConfig config = parse(yaml, 100, 200);
        assertTrue(config.enabled(), "shipped defaults must be valid");

        assertEquals(3, config.claimSize().maxLevel(), "claim-size has 3 levels");
        assertEquals(10, config.claimSize().growthPerLevel());
        assertEquals(2500, config.claimSizePrice(1), 0.001);
        assertEquals(10000, config.claimSizePrice(2), 0.001);
        assertEquals(30000, config.claimSizePrice(3), 0.001);
        assertEquals(-1, config.claimSizePrice(4), "no level 4");

        assertEquals(3, config.memberSlots().baseSlots());
        assertEquals(3, config.memberLimit(0), "base member limit is 3");
        assertEquals(4, config.memberLimit(1));
        assertEquals(6, config.memberLimit(99), "limit is clamped to maxLevel");
        assertEquals(2000, config.memberSlotsPrice(1), 0.001);
        assertEquals(-1, config.memberSlotsPrice(4));

        assertEquals(100, config.borderSizeFor(100, 0), "level 0 keeps the base border");
        assertEquals(110, config.borderSizeFor(100, 1));
        assertEquals(130, config.borderSizeFor(100, 3), "full claim expansion");
        assertEquals(130, config.borderSizeFor(100, 7), "clamped to maxLevel");

        final List<IslandUpgradeConfig.BuffDef> buffs = config.buffs();
        assertEquals(3, buffs.size(), "crop-growth, spawner-boost, xp-boost");
        assertEquals("crop-growth", buffs.get(0).id());
        assertEquals("Green Thumb", buffs.get(0).name());
        assertEquals(Material.WHEAT, buffs.get(0).icon());
        assertEquals(30, buffs.get(0).durationMinutes());
        assertEquals(1500, buffs.get(0).price(), 0.001);
        assertEquals(2, buffs.get(0).multiplier(), 0.001);
        assertNotNull(config.buff("spawner-boost"));
        assertNotNull(config.buff("xp-boost"));
    }

    @Test
    void fullyUpgradedClaimMustStayBelowTheGridSpacing() {
        // base 100 + 10 x 3 levels = 130 < 200: fine
        assertTrue(parse(DEFAULTS, 100, 200).enabled());
        // spacing 120 would let maxed claims touch: rejected loudly
        assertThrows(IllegalArgumentException.class, () -> parse(DEFAULTS, 100, 120));
    }

    @Test
    void unknownMaterialIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(DEFAULTS.replace("icon: GRASS_BLOCK", "icon: NOT_A_BLOCK"), 100, 200));
    }

    @Test
    void negativePriceIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(DEFAULTS.replace("steps: [2500, 10000, 30000]", "steps: [2500, -5]"),
                        100, 200));
    }

    @Test
    void multiplierBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(DEFAULTS.replace("multiplier: 2", "multiplier: 0.5"), 100, 200));
    }

    @Test
    void emptyStepsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(DEFAULTS.replace("steps: [2500, 10000, 30000]", "steps: []"), 100, 200));
    }

    @Test
    void missingSectionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("other: 1", 100, 200));
        assertThrows(IllegalArgumentException.class,
                () -> parse(DEFAULTS.replaceFirst("(?s)buffs:.*", ""), 100, 200));
    }

    @Test
    void disabledFallbackLeavesMembersUncappedAndBorderUnchanged() {
        final IslandUpgradeConfig config = IslandUpgradeConfig.disabled();
        assertFalse(config.enabled());
        assertEquals(Integer.MAX_VALUE, config.memberLimit(0), "no cap");
        assertEquals(Integer.MAX_VALUE, config.memberLimit(5));
        assertEquals(100, config.borderSizeFor(100, 3), "border stays at base");
        assertTrue(config.buffs().isEmpty());
    }

    private IslandUpgradeConfig parse(final String yaml, final int baseBorder, final int spacing) {
        final IslandUpgradeConfig config = new IslandUpgradeConfig(null, null);
        final YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.loadFromString(yaml);
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(loaded, baseBorder, spacing);
        return config;
    }
}
