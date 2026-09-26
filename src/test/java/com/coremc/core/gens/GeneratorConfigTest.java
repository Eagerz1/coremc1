package com.coremc.core.gens;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Generator config parsing: the shipped generators.yml is parsed here
 * too, so a typo in the shipped progression fails the build instead
 * of breaking servers. Validation must be loud and complete.
 */
class GeneratorConfigTest {

    private static GeneratorConfig parse(final String yaml) {
        final GeneratorConfig config = new GeneratorConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(parsed);
        return config;
    }

    private static GeneratorConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/generators.yml"),
                StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // the shipped progression
    // ------------------------------------------------------------------

    @Test
    void bundledConfigShipsTenGeneratorsInTierOrder() throws IOException {
        final GeneratorConfig config = bundled();
        final List<GeneratorTier> tiers = config.all();
        assertEquals(10, tiers.size());
        for (int index = 0; index < tiers.size(); index++) {
            assertEquals(index + 1, tiers.get(index).tier(),
                    tiers.get(index).id() + " is out of tier order");
        }
        assertEquals("cobblestone", tiers.get(0).id());
        assertEquals("netherite", tiers.get(9).id());
        assertTrue(config.enabled());
    }

    @Test
    void bundledUpgradePathIsOneUnbrokenLadder() throws IOException {
        final GeneratorConfig config = bundled();
        GeneratorTier tier = config.byId("cobblestone");
        int steps = 0;
        while (tier.hasUpgrade()) {
            final GeneratorTier next = config.next(tier);
            assertNotNull(next, tier.id() + " upgrades into a real generator");
            assertTrue(next.tier() > tier.tier(), "upgrades always climb");
            assertTrue(tier.upgradeCost() > 0, tier.id() + " has an upgrade cost");
            tier = next;
            steps++;
        }
        assertEquals("netherite", tier.id());
        assertEquals(9, steps, "ten generators means nine upgrade steps");
        assertNull(config.next(tier), "the top tier has no upgrade");
    }

    @Test
    void bundledGeneratorsHaveDistinctBlocksAndSanePayouts() throws IOException {
        final GeneratorConfig config = bundled();
        final Set<Material> blocks = new HashSet<>();
        double previousValue = 0;
        for (final GeneratorTier tier : config.all()) {
            assertTrue(blocks.add(tier.block()),
                    tier.id() + " reuses the block " + tier.block());
            assertNotNull(tier.output(), tier.id() + " produces an item");
            assertTrue(tier.price() > 0, tier.id() + " costs coins");
            assertTrue(tier.value() > previousValue,
                    tier.id() + " must out-earn the tier below it");
            assertTrue(tier.maxPlaced() > 0, tier.id() + " has a placement cap");
            previousValue = tier.value();
        }
    }

    @Test
    void bundledIntervalsGetFasterAsTheLadderClimbs() throws IOException {
        final GeneratorConfig config = bundled();
        int previous = Integer.MAX_VALUE;
        for (final GeneratorTier tier : config.all()) {
            assertTrue(tier.intervalSeconds() <= previous,
                    tier.id() + " should not be slower than the tier below");
            previous = tier.intervalSeconds();
        }
    }

    @Test
    void bundledSettingsLoad() throws IOException {
        final GeneratorConfig config = bundled();
        assertEquals(64, config.maxStack());
        assertEquals(64, config.maxPerIsland());
        assertTrue(config.requireOnline());
        assertTrue(config.produceItems());
        assertTrue(config.holograms());
    }

    @Test
    void ironMatchesTheDesignedProgression() throws IOException {
        final GeneratorTier iron = bundled().byId("iron");
        assertEquals(3, iron.tier());
        assertEquals("III", iron.tierNumeral());
        assertEquals(30, iron.intervalSeconds());
        assertEquals(450.0, iron.value());
        assertEquals(25000.0, iron.price());
        assertEquals("gold", iron.upgradeTo());
        assertEquals(50000.0, iron.upgradeCost());
        assertEquals(Material.IRON_BLOCK, iron.block());
        assertEquals(Material.IRON_INGOT, iron.output());
    }

    // ------------------------------------------------------------------
    // defaults
    // ------------------------------------------------------------------

    @Test
    void settingsHaveSaneDefaults() {
        final GeneratorConfig config = parse(minimal());
        assertEquals(64, config.maxStack());
        assertEquals(64, config.maxPerIsland());
        assertTrue(config.requireOnline());
        assertTrue(config.produceItems());
        assertTrue(config.holograms());
        final GeneratorTier tier = config.byId("basic");
        assertEquals(Material.COBBLESTONE, tier.icon(), "icon defaults to the block");
        assertEquals(16, tier.maxPlaced());
        assertEquals(0.0, tier.requiredPoints());
        assertFalse(tier.hasUpgrade());
        assertEquals(20 * 30, tier.intervalTicks());
    }

    @Test
    void lookupsAreCaseInsensitiveAndNullSafe() {
        final GeneratorConfig config = parse(minimal());
        assertNotNull(config.byId("BASIC"));
        assertNull(config.byId("nope"));
        assertNull(config.byId(null));
        assertNull(config.next(null));
    }

    @Test
    void aDisabledConfigIsEmptyButUsable() {
        final GeneratorConfig config = GeneratorConfig.disabled();
        assertFalse(config.enabled());
        assertTrue(config.all().isEmpty());
        assertNull(config.byId("iron"));
    }

    // ------------------------------------------------------------------
    // validation — every problem must be loud
    // ------------------------------------------------------------------

    @Test
    void missingGeneratorsSectionIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("settings:\n  max-stack: 8\n"));
        assertTrue(error.getMessage().contains("generators"));
    }

    @Test
    void unknownMaterialIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(doc(gen("basic", "name: \"Basic\"", "tier: 1", "price: 100",
                        "interval: 30", "value: 10", "block: NOT_A_BLOCK"))));
        assertTrue(error.getMessage().contains("NOT_A_BLOCK"));
    }

    @Test
    void materialTypeChecksNeverCrashWithoutABukkitRegistry() {
        // block/item type checks only run on a live server; parsing a
        // config off-server (tests, CI) must still work
        final GeneratorConfig config = parse(doc(gen("basic", "name: \"Basic\"", "tier: 1",
                "price: 100", "interval: 30", "value: 10", "block: COBBLESTONE",
                "output:", "  material: IRON_INGOT", "  amount: 4")));
        assertEquals(Material.IRON_INGOT, config.byId("basic").output());
        assertEquals(4, config.byId("basic").outputAmount());
    }

    @Test
    void duplicateTiersAreRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(doc(
                        gen("basic", "name: \"Basic\"", "tier: 1", "price: 100",
                                "interval: 30", "value: 10", "block: COBBLESTONE"),
                        gen("twin", "name: \"Twin\"", "tier: 1", "price: 100",
                                "interval: 30", "value: 10", "block: COAL_BLOCK"))));
        assertTrue(error.getMessage().contains("tier 1 is used twice"));
    }

    @Test
    void upgradeToAnUnknownGeneratorIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(doc(gen("basic", "name: \"Basic\"", "tier: 1", "price: 100",
                        "interval: 30", "value: 10", "block: COBBLESTONE",
                        "upgrade:", "  to: ghost", "  cost: 5"))));
        assertTrue(error.getMessage().contains("unknown generator 'ghost'"));
    }

    @Test
    void upgradeToALowerTierIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(doc(
                        gen("high", "name: \"High\"", "tier: 2", "price: 100",
                                "interval: 30", "value: 10", "block: COBBLESTONE",
                                "upgrade:", "  to: low", "  cost: 5"),
                        gen("low", "name: \"Low\"", "tier: 1", "price: 100",
                                "interval: 30", "value: 10", "block: COAL_BLOCK"))));
        assertTrue(error.getMessage().contains("not a higher tier"));
    }

    @Test
    void upgradingIntoItselfIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(doc(gen("basic", "name: \"Basic\"", "tier: 1", "price: 100",
                        "interval: 30", "value: 10", "block: COBBLESTONE",
                        "upgrade:", "  to: basic", "  cost: 5"))));
        assertTrue(error.getMessage().contains("cannot upgrade into itself"));
    }

    @Test
    void anUpgradeWithoutACostIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(doc(
                        gen("basic", "name: \"Basic\"", "tier: 1", "price: 100",
                                "interval: 30", "value: 10", "block: COBBLESTONE",
                                "upgrade:", "  to: better"),
                        gen("better", "name: \"Better\"", "tier: 2", "price: 200",
                                "interval: 20", "value: 20", "block: COAL_BLOCK"))));
        assertTrue(error.getMessage().contains("cost"));
    }

    @Test
    void badNumbersAreRejected() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parse(doc(gen("basic", "name: \"Basic\"", "tier: 1", "price: 100",
                        "interval: 0", "value: 10", "block: COBBLESTONE"))))
                .getMessage().contains("interval"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parse(doc(gen("basic", "name: \"Basic\"", "tier: 1", "price: -1",
                        "interval: 30", "value: 10", "block: COBBLESTONE"))))
                .getMessage().contains("price"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parse(doc(gen("basic", "name: \"Basic\"", "tier: 0", "price: 100",
                        "interval: 30", "value: 10", "block: COBBLESTONE"))))
                .getMessage().contains("tier"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parse(doc(gen("basic", "tier: 1", "price: 100",
                        "interval: 30", "value: 10", "block: COBBLESTONE"))))
                .getMessage().contains("name"));
    }

    @Test
    void aBadColourCodeIsRejected() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parse(doc(gen("basic", "name: \"Basic\"", "tier: 1", "price: 100",
                        "interval: 30", "value: 10", "block: COBBLESTONE", "color: red"))))
                .getMessage().contains("colour code"));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static String minimal() {
        return doc(gen("basic", "name: \"Basic Generator\"", "tier: 1", "price: 100",
                "interval: 30", "value: 10", "block: COBBLESTONE"));
    }

    /** Wraps generator bodies into a whole document. */
    private static String doc(final String... bodies) {
        return "generators:\n" + String.join("", bodies);
    }

    /** One generator section from raw (already relative) YAML lines. */
    private static String gen(final String id, final String... lines) {
        final StringBuilder out = new StringBuilder("  " + id + ":\n");
        for (final String line : lines) {
            out.append("    ").append(line).append("\n");
        }
        return out.toString();
    }
}
