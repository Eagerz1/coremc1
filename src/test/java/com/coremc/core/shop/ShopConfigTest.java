package com.coremc.core.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Shop catalogue parsing: order, defaults, and loud validation. The
 * bundled default shop.yml is itself parsed here, so a typo in the
 * shipped catalogue fails the build instead of breaking servers.
 */
class ShopConfigTest {

    private static final String VALID = """
            starting-balance: 50.0
            currency-symbol: "c"
            sections:
              building:
                name: "Building Blocks"
                icon: BRICKS
                items:
                  - "COBBLESTONE:1:0.25"
                  - "OAK_LOG:3"
            """;

    @Test
    void parsesSectionsOrderNamesAndItems() {
        final ShopConfig config = parse(VALID);
        final List<ShopSection> sections = config.sections();
        assertEquals(1, sections.size());
        assertEquals("building", sections.get(0).id());
        assertEquals("Building Blocks", sections.get(0).name());
        assertEquals(Material.BRICKS, sections.get(0).icon());
        assertEquals(2, sections.get(0).itemCount());

        final ShopItem cobble = sections.get(0).item(0);
        assertEquals(Material.COBBLESTONE, cobble.material());
        assertEquals(1.0, cobble.buyPrice());
        assertEquals(0.25, cobble.sellPrice());
        assertEquals("Cobblestone", cobble.displayName());
    }

    @Test
    void sellPriceDefaultsToQuarterOfBuy() {
        final ShopConfig config = parse(VALID);
        assertEquals(0.75, config.sections().get(0).item(1).sellPrice());
    }

    @Test
    void sectionLookupById() {
        final ShopConfig config = parse(VALID);
        assertNotNull(config.section("building"));
        assertNull(config.section("nope"));
    }

    @Test
    void unknownMaterialIsRejected() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("COBBLESTONE:1:0.25", "NOT_A_BLOCK:1:0.25")));
        assertTrue(exception.getMessage().contains("NOT_A_BLOCK"));
    }

    @Test
    void sellAboveBuyIsRejectedAsInfiniteMoneyLoop() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("COBBLESTONE:1:0.25", "COBBLESTONE:1:2")));
        assertTrue(exception.getMessage().contains("infinite money loop"));
    }

    @Test
    void duplicateMaterialInASectionIsRejected() {
        final String duplicated = """
                sections:
                  building:
                    name: "Building Blocks"
                    icon: BRICKS
                    items:
                      - "COBBLESTONE:1:0.25"
                      - "COBBLESTONE:2:0.5"
                """;
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parse(duplicated));
        assertTrue(exception.getMessage().contains("duplicate material"));
    }

    @Test
    void missingSectionsMappingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("starting-balance: 10.0\n"));
    }

    @Test
    void moreThanSevenSectionsIsRejected() {
        final StringBuilder yaml = new StringBuilder("sections:\n");
        for (int i = 0; i < 8; i++) {
            yaml.append("  s").append(i).append(":\n    name: \"S").append(i)
                    .append("\"\n    icon: CHEST\n    items:\n      - \"DIRT:1\"\n");
        }
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parse(yaml.toString()));
        assertTrue(exception.getMessage().contains("only has 7 slots"));
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        final String broken = VALID
                .replace("COBBLESTONE:1:0.25", "COBBLESTONE:1:2")
                .replace("OAK_LOG:3", "NOPE_NOT_REAL:3");
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parse(broken));
        assertTrue(exception.getMessage().contains("NOPE_NOT_REAL"));
        assertTrue(exception.getMessage().contains("infinite money loop"));
    }

    /** The shipped catalogue must load clean: 7 sections, valid materials, sane prices. */
    @Test
    void bundledDefaultCatalogueLoadsClean() throws IOException {
        final Path bundled = Path.of("src/main/resources/shop.yml");
        final String yaml = Files.readString(bundled, StandardCharsets.UTF_8);
        final ShopConfig config = parse(yaml);

        assertEquals(7, config.sections().size());
        assertEquals(100.0, config.startingBalance());
        assertEquals("$", config.currencySymbol());

        for (final ShopSection section : config.sections()) {
            assertTrue(section.itemCount() > 0, section.id() + " has items");
            for (final ShopItem item : section.items()) {
                assertTrue(item.buyPrice() > 0 || item.sellPrice() > 0,
                        section.id() + "/" + item.material() + " is tradeable");
                assertTrue(item.sellPrice() <= item.buyPrice(),
                        section.id() + "/" + item.material() + " is not an infinite money loop");
            }
        }
        // Known entries.
        assertEquals(Material.COBBLESTONE, config.section("building").item(0).material());
        assertEquals(Material.DIAMOND,
                config.section("minerals").items().stream()
                        .filter(item -> item.material() == Material.DIAMOND).findFirst().orElseThrow()
                        .material());
        // At least one section needs a second page so pagination is exercised.
        assertTrue(ShopLayout.pageCount(config.section("building").itemCount()) > 1,
                "building section spans multiple pages");
    }

    private ShopConfig parse(final String yamlText) {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(yamlText);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        final ShopConfig config = new ShopConfig(null);
        config.parse(yaml);
        return config;
    }
}
