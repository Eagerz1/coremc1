package com.coremc.core.role;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmniUpgradeCatalogTest {

    @Test
    void guiOrderMatchesSpecSlots() {
        assertEquals(List.of("efficiency", "fortune", "smelter"), OmniUpgradeCatalog.GUI_ORDER);
    }

    @Test
    void upgradeCostIndexing() {
        final OmniUpgradeCatalog.Upgrade def =
                new OmniUpgradeCatalog.Upgrade("efficiency", "&eE", 3, List.of(100L, 200L, 400L));
        assertEquals(100L, def.costForNextLevel(0)); // 0 -> 1
        assertEquals(200L, def.costForNextLevel(1)); // 1 -> 2
        assertEquals(400L, def.costForNextLevel(2)); // 2 -> 3 (max)
        assertEquals(-1L, def.costForNextLevel(3)); // maxed
        assertTrue(def.maxed(3));
        assertFalse(def.maxed(2));
    }

    @Test
    void upgradeValidationRejectsBadCatalogEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> new OmniUpgradeCatalog.Upgrade("", "&fX", 1, List.of(0L)));
        assertThrows(IllegalArgumentException.class,
                () -> new OmniUpgradeCatalog.Upgrade("x", "&fX", 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new OmniUpgradeCatalog.Upgrade("x", "&fX", 2, List.of(1L))); // one cost short
        assertThrows(IllegalArgumentException.class,
                () -> new OmniUpgradeCatalog.Upgrade("x", "&fX", 1, List.of(-5L)));
    }

    @Test
    void smelterTableCoversOresAndStaysAwayFromBlocks() {
        assertEquals(Material.IRON_INGOT, OmniUpgradeCatalog.smeltedResult(Material.IRON_ORE));
        assertEquals(Material.IRON_INGOT, OmniUpgradeCatalog.smeltedResult(Material.DEEPSLATE_IRON_ORE));
        assertEquals(Material.GOLD_INGOT, OmniUpgradeCatalog.smeltedResult(Material.GOLD_ORE));
        assertEquals(Material.COPPER_INGOT, OmniUpgradeCatalog.smeltedResult(Material.COPPER_ORE));
        assertEquals(Material.NETHERITE_SCRAP, OmniUpgradeCatalog.smeltedResult(Material.ANCIENT_DEBRIS));
        assertEquals(Material.STONE, OmniUpgradeCatalog.smeltedResult(Material.COBBLESTONE));
        // raw block / storage forms deliberately untouched (no infinite smelting loops)
        assertNull(OmniUpgradeCatalog.smeltedResult(Material.IRON_BLOCK));
        assertNull(OmniUpgradeCatalog.smeltedResult(Material.RAW_IRON_BLOCK));
        assertNull(OmniUpgradeCatalog.smeltedResult(Material.DIRT));
        assertNull(OmniUpgradeCatalog.smeltedResult(null));
    }

    @Test
    void smeltXpMirrorsFurnaceEconomy() {
        assertEquals(1, OmniUpgradeCatalog.smeltXpFor(Material.IRON_ORE));
        assertEquals(1, OmniUpgradeCatalog.smeltXpFor(Material.GOLD_ORE));
        assertEquals(2, OmniUpgradeCatalog.smeltXpFor(Material.ANCIENT_DEBRIS));
        assertEquals(0, OmniUpgradeCatalog.smeltXpFor(Material.COBBLESTONE));
        assertEquals(0, OmniUpgradeCatalog.smeltXpFor(null));
    }

    @Test
    void fortuneRollBoundsAndAverages() {
        // level 0 never yields extras
        assertEquals(0, OmniUpgradeCatalog.fortuneRollAmount(0, new Random(42L)));
        assertEquals(0, OmniUpgradeCatalog.fortuneRollAmount(-1, new Random(42L)));

        for (int level = 1; level <= 5; level++) {
            final Random random = new Random(7L + level);
            final int samples = 20_000;
            long total = 0L;
            for (int i = 0; i < samples; i++) {
                final int extra = OmniUpgradeCatalog.fortuneRollAmount(level, random);
                assertTrue(extra >= 0, "extra must never be negative (level " + level + ")");
                assertTrue(extra <= level, "extra must never exceed the fortune level itself");
                total += extra;
            }
            // analytic expectation: average of max(0, roll-1) over roll in [0, level+2)
            final double expectation = (level * (level + 1) / 2.0) / (level + 2.0);
            final double observed = (double) total / samples;
            assertTrue(Math.abs(observed - expectation) < 0.03,
                    "fortune level " + level + " average " + observed + " drifted from " + expectation);
        }
    }

    @Test
    void catalogueLookupIsImmutable() {
        final OmniUpgradeCatalog catalog = new OmniUpgradeCatalog(Map.of(
                "efficiency", new OmniUpgradeCatalog.Upgrade("efficiency", "&eE", 1, List.of(0L))));
        assertEquals(1, catalog.size());
        assertTrue(catalog.upgrade("efficiency").isPresent());
        assertTrue(catalog.upgrade("nope").isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.all().put("x", null));
    }
}
