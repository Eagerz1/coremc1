package com.coremc.core.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // Building is the grouped catalogue: subcategories in picker
        // order, one page for the small ones, several for the big ones.
        final ShopSection building = config.section("building");
        assertTrue(building.grouped(), "building is grouped");
        assertTrue(building.groups().size() > 1, "building has subcategories");
        assertEquals("stone", building.groups().get(0).id());
        assertEquals(Material.COBBLESTONE, building.groups().get(0).item(0).material());
        assertEquals(1, ShopLayout.pageCount(building.groups().get(0).itemCount()),
                "stone group fits one page");
        assertTrue(building.groups().stream().anyMatch(group -> group.itemCount() > ShopLayout.ITEMS_PER_PAGE),
                "at least one group spans multiple pages so pagination is exercised");
        // Flat sections still paginate the classic way.
        assertTrue(config.sections().stream().anyMatch(section -> !section.grouped()),
                "flat sections still exist");

        // /sell valuation: catalogue prices, the default for everything else.
        assertEquals(0.25, config.defaultSellPrice());
        assertEquals(0.25, config.sellPrice(Material.COBBLESTONE));
        assertEquals(72.0, config.sellPrice(Material.DIAMOND_BLOCK));
        assertEquals(0.25, config.sellPrice(Material.OAK_FENCE), "unlisted -> default");
    }

    @Test
    void defaultSellPriceFallsBackToAQuarterWhenMissing() {
        final ShopConfig config = parse(
                "starting-balance: 100.0\n"
                        + "sections:\n"
                        + "  building:\n"
                        + "    name: \"Building Blocks\"\n"
                        + "    icon: BRICKS\n"
                        + "    items:\n"
                        + "      - \"COBBLESTONE:1:0.25\"\n");
        assertEquals(0.25, config.defaultSellPrice());
        assertEquals(0.25, config.sellPrice(Material.OAK_FENCE));
    }

    @Test
    void catalogueRefusedMaterialsSellForZero() {
        final ShopConfig config = parse(
                "sections:\n"
                        + "  building:\n"
                        + "    name: \"Building Blocks\"\n"
                        + "    icon: BRICKS\n"
                        + "    items:\n"
                        + "      - \"COBBLESTONE:1:0.25\"\n"
                        + "      - \"BEDROCK:5:0\"\n");
        assertEquals(0.0, config.sellPrice(Material.BEDROCK),
                "a catalogue SELL of 0 refuses the material in /sell");
        assertEquals(0.25, config.sellPrice(Material.COBBLESTONE));
    }

    @Test
    void theSameMaterialCannotBeListedInTwoSections() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(
                        "sections:\n"
                                + "  one:\n"
                                + "    name: \"One\"\n"
                                + "    icon: BRICKS\n"
                                + "    items:\n"
                                + "      - \"COBBLESTONE:1:0.25\"\n"
                                + "  two:\n"
                                + "    name: \"Two\"\n"
                                + "    icon: CHEST\n"
                                + "    items:\n"
                                + "      - \"COBBLESTONE:2:0.5\"\n"));
        assertTrue(error.getMessage().contains("'one'"), error.getMessage());
        assertTrue(error.getMessage().contains("'two'"), error.getMessage());
    }

    // --------------------------------------------------------------
    // Grouped sections (subcategories)
    // --------------------------------------------------------------

    private static final String GROUPED = """
            sections:
              building:
                name: "Building"
                icon: BRICKS
                groups:
                  stone:
                    name: "Stone & Granite"
                    icon: STONE
                    items:
                      - "COBBLESTONE:1:0.25"
                      - "STONE:1.5:0.4"
                  wood:
                    name: "Wood"
                    icon: OAK_PLANKS
                    items:
                      - "OAK_PLANKS:2:0.5"
              crops:
                name: "Farming"
                icon: WHEAT
                items:
                  - "WHEAT_SEEDS:1:0.25"
            """;

    @Test
    void groupedSectionsParseGroupsInOrder() {
        final ShopConfig config = parse(GROUPED);
        final ShopSection building = config.section("building");
        assertTrue(building.grouped(), "building is grouped");
        assertEquals(2, building.groups().size());
        assertEquals("stone", building.groups().get(0).id());
        assertEquals("Stone & Granite", building.groups().get(0).name());
        assertEquals(Material.STONE, building.groups().get(0).icon());
        assertEquals(2, building.groups().get(0).itemCount());
        assertEquals(1, building.groups().get(1).itemCount());
        // flat sections are not grouped
        assertFalse(config.section("crops").grouped());
        assertEquals(0, config.section("crops").groups().size());
    }

    @Test
    void groupedSectionItemsAreFlattenedInGroupOrder() {
        final ShopConfig config = parse(GROUPED);
        final ShopSection building = config.section("building");
        assertEquals(3, building.itemCount());
        assertEquals(Material.COBBLESTONE, building.item(0).material());
        assertEquals(Material.STONE, building.item(1).material());
        assertEquals(Material.OAK_PLANKS, building.item(2).material());
        // group lookup by id, case-insensitive
        assertEquals("wood", building.group("Wood").id());
        assertNull(building.group("nope"));
    }

    @Test
    void groupedItemsArePricedForSellLikeFlatOnes() {
        final ShopConfig config = parse(GROUPED);
        assertEquals(0.25, config.sellPrice(Material.COBBLESTONE));
        assertEquals(0.5, config.sellPrice(Material.OAK_PLANKS));
        assertEquals(0.25, config.sellPrice(Material.OAK_FENCE), "unlisted -> default");
    }

    @Test
    void groupIconDefaultsToTheFirstItemWhenOmitted() {
        final ShopConfig config = parse(GROUPED.replace("        icon: STONE\n", ""));
        assertEquals(Material.COBBLESTONE, config.section("building").groups().get(0).icon());
    }

    @Test
    void groupNameDefaultsToItsId() {
        final ShopConfig config = parse(GROUPED
                .replace("        name: \"Wood\"\n", "")
                .replace("        icon: OAK_PLANKS\n", ""));
        assertEquals("wood", config.section("building").group("wood").name());
        assertEquals(Material.OAK_PLANKS, config.section("building").group("wood").icon());
    }

    @Test
    void duplicateMaterialAcrossGroupsIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        sections:
                          building:
                            name: "Building"
                            icon: BRICKS
                            groups:
                              stone:
                                items:
                                  - "COBBLESTONE:1:0.25"
                              extra:
                                items:
                                  - "COBBLESTONE:2:0.5"
                        """));
        // the duplicate must name the group it happened in
        assertTrue(error.getMessage().contains("duplicate material"), error.getMessage());
        assertTrue(error.getMessage().contains("group 'extra'"), error.getMessage());
    }

    @Test
    void itemsAndGroupsInOneSectionIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(GROUPED.replace("    icon: BRICKS\n",
                        "    icon: BRICKS\n    items:\n      - \"DIRT:1:0.25\"\n")));
        assertTrue(error.getMessage().contains("both 'items' and 'groups'"), error.getMessage());
    }

    @Test
    void emptyGroupIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(GROUPED.replace("          - \"OAK_PLANKS:2:0.5\"\n", "")));
        assertTrue(error.getMessage().contains("group 'wood': no valid items"), error.getMessage());
    }

    @Test
    void unknownGroupIconIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(GROUPED.replace("icon: STONE", "icon: NOT_A_BLOCK")));
        assertTrue(error.getMessage().contains("group 'stone'"), error.getMessage());
        assertTrue(error.getMessage().contains("NOT_A_BLOCK"), error.getMessage());
    }

    @Test
    void moreThanFourteenGroupsIsRejected() {
        final StringBuilder yaml = new StringBuilder("sections:\n  building:\n    name: \"B\"\n    icon: BRICKS\n    groups:\n");
        for (int i = 0; i < 15; i++) {
            yaml.append("      g").append(i).append(":\n        name: \"G").append(i)
                    .append("\"\n        items:\n          - \"DIRT:1:0.25\"\n");
        }
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(yaml.toString()));
        assertTrue(error.getMessage().contains("more than 14 groups"), error.getMessage());
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
