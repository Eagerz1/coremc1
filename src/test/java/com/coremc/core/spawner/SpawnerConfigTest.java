package com.coremc.core.spawner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/**
 * Spawner config parsing: the shipped spawners.yml is itself parsed
 * here, so a typo in the shipped progression fails the build instead
 * of breaking servers. Validation must be loud and complete.
 */
class SpawnerConfigTest {

    private static SpawnerConfig parse(final String yaml) {
        final SpawnerConfig config = new SpawnerConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(parsed);
        return config;
    }

    private static SpawnerConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/spawners.yml"),
                StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // the shipped catalogue
    // ------------------------------------------------------------------

    @Test
    void bundledConfigLoadsAllFiveGroupsWithThreeMobs() throws IOException {
        final SpawnerConfig config = bundled();
        assertEquals(5, config.groups().size());
        for (final SpawnerGroup group : config.groups()) {
            assertEquals(3, group.mobs().size(),
                    group.id() + " should have three mobs");
        }
        assertEquals("organic", config.groups().get(0).id());
        assertEquals("Organic", config.groups().get(0).name());
    }

    @Test
    void everyGroupHasDistinctEssenceAndRelicMaterials() throws IOException {
        final SpawnerConfig config = bundled();
        assertEquals(Material.PRISMARINE_SHARD, config.group("organic").essenceMaterial());
        assertEquals("Organic Essence", config.group("organic").essenceName());
        assertEquals(Material.HEART_OF_THE_SEA, config.group("organic").relicMaterial());
        assertEquals(Material.ENDER_PEARL, config.group("ender").essenceMaterial());
        assertEquals("Ender Essence", config.group("ender").essenceName());
        assertEquals(Material.WITHER_ROSE, config.group("corrupted").relicMaterial());
    }

    @Test
    void spawnerPricesEscalateWithinEachGroup() throws IOException {
        final SpawnerConfig config = bundled();
        for (final SpawnerGroup group : config.groups()) {
            assertEquals(2500, group.mobs().get(0).spawnerCost(), group.id() + " first mob");
            assertEquals(5000, group.mobs().get(1).spawnerCost(), group.id() + " second mob");
            assertEquals(10000, group.mobs().get(2).spawnerCost(), group.id() + " third mob");
        }
    }

    @Test
    void unlockRequirementsChainMobsWithinTheGroup() throws IOException {
        final SpawnerConfig config = bundled();
        final SpawnerMob pig = config.group("organic").mob("pig");
        final SpawnerMob cow = config.group("organic").mob("cow");
        final SpawnerMob sheep = config.group("organic").mob("sheep");
        assertEquals(0, pig.unlockEssence());
        assertTrue(pig.unlockDrops().isEmpty());
        assertEquals(15, cow.unlockEssence());
        assertEquals(4, cow.unlockDrops().get("pig"));
        assertEquals(30, sheep.unlockEssence());
        assertEquals(8, sheep.unlockDrops().get("cow"));
    }

    @Test
    void upgradeCostsAreCompleteForEveryMob() throws IOException {
        final SpawnerConfig config = bundled();
        for (final SpawnerGroup group : config.groups()) {
            for (final SpawnerMob mob : group.mobs()) {
                assertEquals(new SpawnerUpgradeCost(10, 1, 0),
                        mob.upgradeCost(SpawnerVariant.ADVANCED), mob.id());
                assertEquals(new SpawnerUpgradeCost(20, 3, 0),
                        mob.upgradeCost(SpawnerVariant.ANCIENT), mob.id());
                assertEquals(new SpawnerUpgradeCost(50, 8, 1),
                        mob.upgradeCost(SpawnerVariant.MYTHIC), mob.id());
                assertNull(mob.upgradeCost(SpawnerVariant.NORMAL), mob.id());
            }
        }
    }

    @Test
    void mobLookupByEntityWorks() throws IOException {
        final SpawnerConfig config = bundled();
        assertEquals("pig", config.mobByEntity(EntityType.PIG).id());
        assertEquals("wither-skeleton", config.mobByEntity(EntityType.WITHER_SKELETON).id());
        assertEquals("shulker", config.mobByEntity(EntityType.SHULKER).id());
        assertNull(config.mobByEntity(EntityType.CREEPER));
        assertNull(config.mobByEntity(EntityType.PLAYER));
        assertEquals("organic", config.groupOf("pig").id());
        assertEquals("ender", config.groupOf("enderman").id());
        assertNull(config.groupOf("creeper"));
    }

    @Test
    void uniqueDropMaterialsAndNames() throws IOException {
        final SpawnerConfig config = bundled();
        assertEquals(Material.BONE, config.group("organic").mob("pig").dropMaterial());
        assertEquals("Pig Tusk", config.group("organic").mob("pig").dropName());
        assertEquals(Material.ENDER_EYE, config.group("ender").mob("enderman").dropMaterial());
        assertEquals("Enderman Focus", config.group("ender").mob("enderman").dropName());
        assertEquals(Material.PHANTOM_MEMBRANE, config.group("ender").mob("endermite").dropMaterial());
    }

    // ------------------------------------------------------------------
    // variant behaviour + luck maths
    // ------------------------------------------------------------------

    @Test
    void variantSettingsGiveRealBehaviouralDifferences() throws IOException {
        final SpawnerConfig config = bundled();
        final SpawnerVariantSettings normal = config.variantSettings(SpawnerVariant.NORMAL);
        final SpawnerVariantSettings mythic = config.variantSettings(SpawnerVariant.MYTHIC);
        assertEquals(1.0, normal.rate());
        assertEquals(2, normal.count());
        assertEquals(8, normal.nearbyLimit());
        assertEquals(false, normal.autoKill());
        assertEquals(4.0, mythic.rate());
        assertEquals(6, mythic.count());
        assertEquals(24, mythic.nearbyLimit());
        assertTrue(mythic.autoKill());
        assertEquals(2.0, config.variantSettings(SpawnerVariant.ADVANCED).rate());
        assertEquals(3.0, config.variantSettings(SpawnerVariant.ANCIENT).rate());
    }

    @Test
    void delaysScaleWithVariantRate() throws IOException {
        final SpawnerConfig config = bundled();
        assertEquals(100, config.minDelayTicks());
        assertEquals(400, config.maxDelayTicks());
        assertEquals(16, config.playerRange());
        final SpawnerVariantSettings normal = config.variantSettings(SpawnerVariant.NORMAL);
        final SpawnerVariantSettings mythic = config.variantSettings(SpawnerVariant.MYTHIC);
        assertEquals(100, normal.delays(100, 400)[0]);
        assertEquals(400, normal.delays(100, 400)[1]);
        assertEquals(25, mythic.delays(100, 400)[0]);
        assertEquals(100, mythic.delays(100, 400)[1]);
    }

    @Test
    void luckChanceGrowsFromTenToFiftyPercent() throws IOException {
        final SpawnerConfig config = bundled();
        assertEquals(0.1, config.uniqueDropChance(0), 1e-9);
        assertEquals(0.2, config.uniqueDropChance(1), 1e-9);
        assertEquals(0.5, config.uniqueDropChance(4), 1e-9);
        assertEquals(4, config.luckMaxLevel());
        assertEquals(750, config.luckCost(1));
        assertEquals(2500, config.luckCost(2));
        assertEquals(7500, config.luckCost(3));
        assertEquals(20000, config.luckCost(4));
        assertEquals(-1, config.luckCost(0));
        assertEquals(-1, config.luckCost(5));
    }

    @Test
    void dropChancesFromSettings() throws IOException {
        final SpawnerConfig config = bundled();
        assertEquals(0.25, config.essenceChance(), 1e-9);
        assertEquals(0.01, config.relicChance(), 1e-9);
        assertEquals(false, config.announceEssence());
    }

    // ------------------------------------------------------------------
    // variants enum
    // ------------------------------------------------------------------

    @Test
    void variantOrderAndLookup() {
        assertEquals(SpawnerVariant.ADVANCED, SpawnerVariant.NORMAL.next());
        assertEquals(SpawnerVariant.ANCIENT, SpawnerVariant.ADVANCED.next());
        assertEquals(SpawnerVariant.MYTHIC, SpawnerVariant.ANCIENT.next());
        assertNull(SpawnerVariant.MYTHIC.next());
        assertEquals(SpawnerVariant.MYTHIC, SpawnerVariant.of("mythic"));
        assertEquals(SpawnerVariant.MYTHIC, SpawnerVariant.of("MYTHIC"));
        assertNull(SpawnerVariant.of("legendary"));
        assertEquals("Normal", SpawnerVariant.NORMAL.display());
    }

    // ------------------------------------------------------------------
    // loud validation
    // ------------------------------------------------------------------

    @Test
    void brokenMaterialIsRejectedLoudly() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(minimalGroups("organic", "item: NOT_A_MATERIAL",
                        "item: HEART_OF_THE_SEA")));
        assertTrue(error.getMessage().contains("NOT_A_MATERIAL"), error.getMessage());
    }

    @Test
    void nonMobEntityIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(minimalGroups("organic", "item: PRISMARINE_SHARD", "item: HEART_OF_THE_SEA")
                        .replace("entity: PIG", "entity: ITEM_FRAME")));
        assertTrue(error.getMessage().contains("is not a mob"), error.getMessage());
    }

    @Test
    void missingVariantIsRejected() {
        final String yaml = minimalGroups("organic", "item: PRISMARINE_SHARD",
                        "item: HEART_OF_THE_SEA")
                .replace("\n  advanced: {rate: 2.0, count: 3, nearby-limit: 12, auto-kill: false}", "");
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(yaml));
        assertTrue(error.getMessage().contains("advanced"), error.getMessage());
    }

    @Test
    void missingUpgradeCostIsRejected() {
        final String yaml = minimalGroups("organic", "essence-item: PRISMARINE_SHARD",
                        "relic-item: HEART_OF_THE_SEA")
                .replace("upgrades: {advanced: {essence: 10, drops: 1}, ancient: {essence: 20, drops: 3}, mythic: {essence: 50, drops: 8, relics: 1}}",
                        "upgrades: {advanced: {essence: 10, drops: 1}}");
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(yaml));
        assertTrue(error.getMessage().contains("ancient"), error.getMessage());
    }

    @Test
    void noGroupsIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        settings: {}
                        variants:
                          normal: {rate: 1.0, count: 2, nearby-limit: 8, auto-kill: false}
                          advanced: {rate: 2.0, count: 3, nearby-limit: 12, auto-kill: false}
                          ancient: {rate: 3.0, count: 4, nearby-limit: 16, auto-kill: false}
                          mythic: {rate: 4.0, count: 6, nearby-limit: 24, auto-kill: true}
                        """));
        assertTrue(error.getMessage().contains("groups"), error.getMessage());
    }

    @Test
    void luckCostsShorterThanMaxLevelRejected() {
        final String yaml = minimalGroups("organic", "essence-item: PRISMARINE_SHARD",
                        "relic-item: HEART_OF_THE_SEA")
                .replace("costs: [750, 2500, 7500, 20000]", "costs: [750, 2500]");
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(yaml));
        assertTrue(error.getMessage().contains("costs"), error.getMessage());
    }

    /** Minimal-but-valid document with one group and one mob, for mutation tests. */
    private static String minimalGroups(final String groupId, final String essence,
                                        final String relic) {
        return """
                settings:
                  essence-chance: 0.25
                  relic-chance: 0.01
                  luck:
                    base-chance: 0.1
                    bonus-per-level: 0.1
                    max-level: 4
                    costs: [750, 2500, 7500, 20000]
                  spawner:
                    min-delay-ticks: 100
                    max-delay-ticks: 400
                    player-range: 16
                variants:
                  normal: {rate: 1.0, count: 2, nearby-limit: 8, auto-kill: false}
                  advanced: {rate: 2.0, count: 3, nearby-limit: 12, auto-kill: false}
                  ancient: {rate: 3.0, count: 4, nearby-limit: 16, auto-kill: false}
                  mythic: {rate: 4.0, count: 6, nearby-limit: 24, auto-kill: true}
                groups:
                  %s:
                    name: "Test"
                    essence: {%s, name: "Test Essence"}
                    relic: {%s, name: "Test Relic"}
                    mobs:
                      pig:
                        entity: PIG
                        name: "Pig"
                        drop: {item: BONE, name: "Pig Tusk"}
                        spawner-cost: 2500
                        unlock: {}
                        upgrades: {advanced: {essence: 10, drops: 1}, ancient: {essence: 20, drops: 3}, mythic: {essence: 50, drops: 8, relics: 1}}
                """.formatted(groupId, essence, relic);
    }
}
