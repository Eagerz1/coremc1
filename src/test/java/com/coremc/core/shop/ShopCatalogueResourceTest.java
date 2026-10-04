package com.coremc.core.shop;

import com.coremc.core.util.RawYaml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the shipped 219-entry Skyblock shop catalogue. */
class ShopCatalogueResourceTest {

    private static final List<String> CATEGORIES = List.of("blocks", "food", "redstone", "misc", "ores");
    private static final Map<String, Integer> EXPECTED_COUNTS = Map.of(
            "blocks", 60,
            "food", 40,
            "redstone", 35,
            "misc", 44,
            "ores", 40);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> catalogue() throws IOException {
        try (InputStream stream = ShopCatalogueResourceTest.class.getResourceAsStream("/shop.yml")) {
            assertNotNull(stream, "shop.yml must be bundled as a plugin resource");
            return RawYaml.parseMap(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void shipsExactlyTwoHundredAndNineteenRegularEntries() throws IOException {
        final Map<String, Object> root = catalogue();
        int total = 0;
        for (final String category : CATEGORIES) {
            final Map<String, Object> entries = (Map<String, Object>) root.get(category);
            assertNotNull(entries, category + " category missing");
            assertEquals(EXPECTED_COUNTS.get(category).intValue(), entries.size(), category + " count changed");
            total += entries.size();
        }
        assertEquals(219, total);
        assertEquals(3, ((Map<String, Object>) root.get("tokens")).size(), "token exchange stays separate");
    }

    @Test
    void entriesUseValidUniqueItemsAndTwentyFivePercentSellPrices() throws IOException {
        final Map<String, Object> root = catalogue();
        final Set<String> ids = new HashSet<>();
        for (final String category : CATEGORIES) {
            final Map<String, Object> entries = (Map<String, Object>) root.get(category);
            for (final Map.Entry<String, Object> row : entries.entrySet()) {
                assertTrue(ids.add(category + ":" + row.getKey()), "duplicate stable id " + row.getKey());
                final Map<String, Object> entry = (Map<String, Object>) row.getValue();
                final String materialName = String.valueOf(entry.get("material"));
                final Material material = Material.matchMaterial(materialName);
                assertNotNull(material, row.getKey() + " has invalid material " + materialName);
                // isItem() consults Paper's live RegistryAccess and is unavailable in plain unit tests.
                assertTrue(material != Material.AIR, row.getKey() + " cannot sell air");
                assertEquals("MONEY", entry.get("currency"), row.getKey() + " must use the soft economy");
                final long buy = ((Number) entry.get("price")).longValue();
                final long sell = ((Number) entry.get("sell-price")).longValue();
                assertTrue(buy > 0L, row.getKey() + " needs a positive buy price");
                assertEquals(buy / 4L, sell, row.getKey() + " sell price must be 25% of buy price");
                assertFalse(String.valueOf(entry.get("display")).contains("<"), "MiniMessage is not allowed");
            }
        }
    }

    @Test
    void categoryKeysMatchThePublicCommandContract() {
        assertEquals(CATEGORIES, java.util.Arrays.stream(ShopCategory.values())
                .filter(category -> category != ShopCategory.TOKENS)
                .map(ShopCategory::key)
                .toList());
    }
}
