package com.coremc.core.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Store layout slot maths + the store.yml settings and the exact
 * Credits GUI strings the spec demands.
 */
class StoreLayoutTest {

    @Test
    void contentSlotsAreCentredOnTheMiddleRow() {
        assertArrayEquals(new int[] {21, 22, 23}, StoreLayout.contentSlots(3));
        assertArrayEquals(new int[] {20, 21, 22, 23}, StoreLayout.contentSlots(4));
        assertArrayEquals(new int[] {20, 21, 22, 23, 24}, StoreLayout.contentSlots(5));
        assertArrayEquals(new int[] {18, 19, 20, 21, 22, 23, 24, 25, 26},
                StoreLayout.contentSlots(9));
        assertEquals(0, StoreLayout.contentSlots(0).length);
    }

    @Test
    void overflowWrapsToTheNextRow() {
        final int[] slots = StoreLayout.contentSlots(11);
        assertEquals(11, slots.length);
        assertEquals(30, slots[9]);
        assertEquals(31, slots[10]);
        for (final int slot : slots) {
            assertTrue(slot >= 18 && slot < 36);
        }
    }

    @Test
    void fixedSlotsStayInsideTheChest() {
        for (final int slot : new int[] {StoreLayout.CREDITS_SLOT, StoreLayout.INFO_SLOT,
                StoreLayout.BACK_SLOT, StoreLayout.CLOSE_SLOT, StoreLayout.ROOT_KEYS_SLOT,
                StoreLayout.ROOT_LOOTBOXES_SLOT, StoreLayout.ROOT_BUNDLES_SLOT}) {
            assertTrue(slot >= 0 && slot < StoreLayout.SIZE);
        }
    }

    @Test
    void priceLineMatchesTheSpecExactly() {
        assertEquals("&7\u1D18\u0280\u026A\u1D04\u1D07: &a250 \u1D04\u0280\u1D07\u1D05\u026A\u1D1Bs &a\u2714",
                StoreGui.priceLine(250, true));
        assertEquals("&7\u1D18\u0280\u026A\u1D04\u1D07: &c250 \u1D04\u0280\u1D07\u1D05\u026A\u1D1Bs &c\u2716",
                StoreGui.priceLine(250, false));
    }

    @Test
    void storeConfigValidatesItsNumbers() throws Exception {
        final StoreConfig config = new StoreConfig(null);
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/store.yml")));
        config.parse(yaml);
        assertEquals(100, config.creditsPerEuro());
        assertEquals(5.0, config.islandTopCreditMultiplier(), 1e-9);

        final YamlConfiguration bad = new YamlConfiguration();
        bad.loadFromString("credits:\n  per-euro: 0\n");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> config.parse(bad));
        final YamlConfiguration badMultiplier = new YamlConfiguration();
        badMultiplier.loadFromString("island-top-credit-multiplier: -1\n");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> config.parse(badMultiplier));
    }
}
