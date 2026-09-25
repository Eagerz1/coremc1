package com.coremc.core.essence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.spawner.SpawnerVariant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * EssenceConfig: the shipped essences.yml parses cleanly (so a typo
 * fails the build, not servers), and validation is loud — the Slayer
 * rates need all four variants, and block maps reject junk materials.
 */
class EssenceConfigTest {

    private static EssenceConfig parse(final String yaml) {
        final EssenceConfig config = new EssenceConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(parsed);
        return config;
    }

    private static EssenceConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/essences.yml"),
                StandardCharsets.UTF_8));
    }

    @Test
    void shippedRatesFollowTheVariantLadder() throws IOException {
        final EssenceConfig config = bundled();
        assertEquals(1, config.slayerPerMob(SpawnerVariant.NORMAL));
        assertEquals(2, config.slayerPerMob(SpawnerVariant.ADVANCED));
        assertEquals(3, config.slayerPerMob(SpawnerVariant.ANCIENT));
        assertEquals(5, config.slayerPerMob(SpawnerVariant.MYTHIC));
    }

    @Test
    void shippedMiningMapCoversOresDeepslateObsidianAndGenerators() throws IOException {
        final EssenceConfig config = bundled();
        assertTrue(config.miningEligible(Material.COBBLESTONE), "generator block");
        assertTrue(config.miningEligible(Material.STONE), "generator block");
        assertTrue(config.miningEligible(Material.COAL_ORE));
        assertTrue(config.miningEligible(Material.DEEPSLATE_COAL_ORE));
        assertTrue(config.miningEligible(Material.DIAMOND_ORE));
        assertTrue(config.miningEligible(Material.DEEPSLATE_DIAMOND_ORE));
        assertTrue(config.miningEligible(Material.OBSIDIAN));
        assertTrue(config.miningEligible(Material.ANCIENT_DEBRIS));
        assertEquals(8, config.miningBlocks().get(Material.DIAMOND_ORE));
        assertEquals(25, config.miningBlocks().get(Material.ANCIENT_DEBRIS));
    }

    @Test
    void shippedFarmingMapCoversAllTaskCrops() throws IOException {
        final EssenceConfig config = bundled();
        for (final Material crop : new Material[]{
                Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
                Material.NETHER_WART, Material.SUGAR_CANE, Material.MELON, Material.PUMPKIN}) {
            assertTrue(config.farmingBlocks().containsKey(crop), crop + " should pay Farming Essence");
        }
    }

    @Test
    void miningAndFarmingMapsAreOptional() {
        final EssenceConfig config = parse("""
                slayer:
                  per-mob:
                    normal: 1
                    advanced: 2
                    ancient: 3
                    mythic: 5
                """);
        assertEquals(5, config.slayerPerMob(SpawnerVariant.MYTHIC));
        assertTrue(config.miningBlocks().isEmpty());
        assertTrue(config.farmingBlocks().isEmpty());
    }

    @Test
    void missingVariantRateIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        slayer:
                          per-mob:
                            normal: 1
                            advanced: 2
                            ancient: 3
                        """));
        assertTrue(error.getMessage().contains("mythic"), error.getMessage());
    }

    @Test
    void negativeRateIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        slayer:
                          per-mob:
                            normal: 1
                            advanced: -2
                            ancient: 3
                            mythic: 5
                        """));
        assertTrue(error.getMessage().contains("advanced"), error.getMessage());
    }

    @Test
    void unknownMaterialInMiningMapIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        slayer:
                          per-mob:
                            normal: 1
                            advanced: 2
                            ancient: 3
                            mythic: 5
                        mining:
                          blocks:
                            NOT_A_BLOCK: 1
                        """));
        assertTrue(error.getMessage().contains("NOT_A_BLOCK"), error.getMessage());
    }

    @Test
    void slayerRewardsScaleWithVariantAndStackSize() throws IOException {
        final EssenceConfig config = bundled();
        // active player kill of a lone normal mob
        assertEquals(1, SlayerRewards.slayerFor(true, SpawnerVariant.NORMAL, 1, config));
        // a 4x advanced stack is worth 8
        assertEquals(8, SlayerRewards.slayerFor(true, SpawnerVariant.ADVANCED, 4, config));
        // a 3x mythic stack is worth 15
        assertEquals(15, SlayerRewards.slayerFor(true, SpawnerVariant.MYTHIC, 3, config));
        // passive deaths pay nothing — no matter the variant or stack
        assertEquals(0, SlayerRewards.slayerFor(false, SpawnerVariant.MYTHIC, 5, config));
        assertEquals(0, SlayerRewards.slayerFor(false, SpawnerVariant.NORMAL, 1, config));
        // a null variant (spawner system off) counts as Normal
        assertEquals(2, SlayerRewards.slayerFor(true, null, 2, config));
        // kills credit follows the same rules
        assertEquals(4, SlayerRewards.killsFor(true, 4));
        assertEquals(0, SlayerRewards.killsFor(false, 4));
        assertEquals(0, SlayerRewards.killsFor(true, 0));
    }
}
