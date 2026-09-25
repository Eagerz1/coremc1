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
import java.util.List;
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
    void stackingSettingsLoad() throws IOException {
        final SpawnerConfig config = bundled();
        assertEquals(64, config.maxSpawnerStack());
        assertTrue(config.mobStackEnabled());
        assertEquals(5, config.mobStackRadius());
        assertEquals(1024, config.mobStackMax());
    }

    @Test
    void stackingSettingsHaveSaneDefaults() {
        final SpawnerConfig config = parse(minimalGroups("organic", "item: HEART_OF_THE_SEA",
                REQUIREMENTS_ALL_TIERS));
        assertEquals(64, config.maxSpawnerStack());
        assertTrue(config.mobStackEnabled());
        assertEquals(5, config.mobStackRadius());
        assertEquals(1024, config.mobStackMax());
    }

    @Test
    void everyGroupHasADistinctRelicMaterial() throws IOException {
        final SpawnerConfig config = bundled();
        assertEquals(Material.HEART_OF_THE_SEA, config.group("organic").relicMaterial());
        assertEquals(Material.NETHER_STAR, config.group("undead").relicMaterial());
        assertEquals(Material.NETHERITE_SCRAP, config.group("infernal").relicMaterial());
        assertEquals(Material.PURPUR_BLOCK, config.group("ender").relicMaterial());
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
    void upgradeRequirementsResolveFromDefaultsGroupAndMobOverrides() throws IOException {
        final SpawnerConfig config = bundled();
        for (final SpawnerGroup group : config.groups()) {
            for (final SpawnerMob mob : group.mobs()) {
                // defaults: $5,000 + 10,000 kills + 20 slayer + 3 own drops
                assertEquals(List.of(
                        new UpgradeRequirement(UpgradeRequirement.Type.MONEY, null, null, 5000),
                        new UpgradeRequirement(UpgradeRequirement.Type.KILLS, null, null, 10000),
                        UpgradeRequirement.essence(com.coremc.core.essence.EssenceType.SLAYER, 20),
                        new UpgradeRequirement(UpgradeRequirement.Type.DROP, null, null, 3)),
                        mob.upgradeRequirements(SpawnerVariant.ADVANCED), mob.id());
                assertNull(mob.upgradeRequirements(SpawnerVariant.NORMAL), mob.id());
                if (mob.id().equals("pig")) {
                    // the starter mob ships a friendlier Ancient (mob-level override)
                    assertEquals(List.of(
                            new UpgradeRequirement(UpgradeRequirement.Type.MONEY, null, null, 15000),
                            new UpgradeRequirement(UpgradeRequirement.Type.KILLS, null, null, 30000),
                            UpgradeRequirement.essence(com.coremc.core.essence.EssenceType.SLAYER, 40),
                            new UpgradeRequirement(UpgradeRequirement.Type.DROP, null, null, 6)),
                            mob.upgradeRequirements(SpawnerVariant.ANCIENT), mob.id());
                } else {
                    assertEquals(List.of(
                            new UpgradeRequirement(UpgradeRequirement.Type.MONEY, null, null, 25000),
                            new UpgradeRequirement(UpgradeRequirement.Type.KILLS, null, null, 50000),
                            UpgradeRequirement.essence(com.coremc.core.essence.EssenceType.SLAYER, 60),
                            new UpgradeRequirement(UpgradeRequirement.Type.DROP, null, null, 8)),
                            mob.upgradeRequirements(SpawnerVariant.ANCIENT), mob.id());
                }
                if (group.id().equals("ender")) {
                    // the endgame group pays more for Mythic (group override)
                    assertEquals(List.of(
                            new UpgradeRequirement(UpgradeRequirement.Type.MONEY, null, null, 150000),
                            new UpgradeRequirement(UpgradeRequirement.Type.KILLS, null, null, 200000),
                            UpgradeRequirement.essence(com.coremc.core.essence.EssenceType.SLAYER, 300),
                            new UpgradeRequirement(UpgradeRequirement.Type.DROP, null, null, 36)),
                            mob.upgradeRequirements(SpawnerVariant.MYTHIC), mob.id());
                } else {
                    assertEquals(List.of(
                            new UpgradeRequirement(UpgradeRequirement.Type.MONEY, null, null, 100000),
                            new UpgradeRequirement(UpgradeRequirement.Type.KILLS, null, null, 150000),
                            UpgradeRequirement.essence(com.coremc.core.essence.EssenceType.SLAYER, 200),
                            new UpgradeRequirement(UpgradeRequirement.Type.DROP, null, null, 25)),
                            mob.upgradeRequirements(SpawnerVariant.MYTHIC), mob.id());
                }
            }
        }
    }

    @Test
    void upgradeTiersEscalateInMoneyKillsEssenceAndDrops() throws IOException {
        final SpawnerConfig config = bundled();
        for (final SpawnerGroup group : config.groups()) {
            for (final SpawnerMob mob : group.mobs()) {
                final List<UpgradeRequirement> advanced = mob.upgradeRequirements(SpawnerVariant.ADVANCED);
                final List<UpgradeRequirement> ancient = mob.upgradeRequirements(SpawnerVariant.ANCIENT);
                final List<UpgradeRequirement> mythic = mob.upgradeRequirements(SpawnerVariant.MYTHIC);
                assertTrue(ancient.stream().mapToDouble(UpgradeRequirement::amount).sum()
                        > advanced.stream().mapToDouble(UpgradeRequirement::amount).sum(), mob.id());
                assertTrue(mythic.stream().mapToDouble(UpgradeRequirement::amount).sum()
                        > ancient.stream().mapToDouble(UpgradeRequirement::amount).sum(), mob.id());
                // every tier asks for money, kills, slayer essence and own drops
                for (final List<UpgradeRequirement> tier : List.of(advanced, ancient, mythic)) {
                    assertEquals(4, tier.size(), mob.id());
                    assertTrue(tier.stream().anyMatch(r -> r.type() == UpgradeRequirement.Type.MONEY));
                    assertTrue(tier.stream().anyMatch(r -> r.type() == UpgradeRequirement.Type.KILLS));
                    assertTrue(tier.stream().anyMatch(r -> r.type() == UpgradeRequirement.Type.ESSENCE));
                    assertTrue(tier.stream().anyMatch(r -> r.type() == UpgradeRequirement.Type.DROP));
                }
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
    void relicChanceFromSettings() throws IOException {
        final SpawnerConfig config = bundled();
        assertEquals(0.01, config.relicChance(), 1e-9);
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
                        REQUIREMENTS_ALL_TIERS)));
        assertTrue(error.getMessage().contains("NOT_A_MATERIAL"), error.getMessage());
    }

    @Test
    void nonMobEntityIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(minimalGroups("organic", "item: HEART_OF_THE_SEA",
                                REQUIREMENTS_ALL_TIERS)
                        .replace("entity: PIG", "entity: ITEM_FRAME")));
        assertTrue(error.getMessage().contains("is not a mob"), error.getMessage());
    }

    @Test
    void missingVariantIsRejected() {
        final String yaml = minimalGroups("organic", "item: HEART_OF_THE_SEA",
                        REQUIREMENTS_ALL_TIERS)
                .replace("\n  advanced: {rate: 2.0, count: 3, nearby-limit: 12, auto-kill: false}", "");
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(yaml));
        assertTrue(error.getMessage().contains("advanced"), error.getMessage());
    }

    @Test
    void missingUpgradeRequirementsForATierAreRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(minimalGroups("organic", "item: HEART_OF_THE_SEA", """
                        upgrades:
                          advanced:
                            - {type: money, amount: 100}
                            - {type: kills, amount: 500}
                            - {type: essence, essence: slayer, amount: 2}
                            - {type: drop, amount: 1}
                        """)));
        assertTrue(error.getMessage().contains("ancient"), error.getMessage());
    }

    @Test
    void unknownRequirementTypeIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(minimalGroups("organic", "item: HEART_OF_THE_SEA", """
                        upgrades:
                          advanced:
                            - {type: money, amount: 100}
                            - {type: blood, amount: 500}
                            - {type: essence, essence: slayer, amount: 2}
                            - {type: drop, amount: 1}
                        """)));
        assertTrue(error.getMessage().contains("type"), error.getMessage());
    }

    @Test
    void essenceRequirementWithoutEssenceKeyIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(minimalGroups("organic", "item: HEART_OF_THE_SEA", """
                        upgrades:
                          advanced:
                            - {type: money, amount: 100}
                            - {type: essence, amount: 2}
                            - {type: drop, amount: 1}
                        """)));
        assertTrue(error.getMessage().contains("essence"), error.getMessage());
    }

    @Test
    void zeroOrNegativeAmountIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(minimalGroups("organic", "item: HEART_OF_THE_SEA", """
                        upgrades:
                          advanced:
                            - {type: money, amount: 0}
                            - {type: drop, amount: 1}
                        """)));
        assertTrue(error.getMessage().contains("amount"), error.getMessage());
    }

    // ------------------------------------------------------------------
    // data-driven variant progression
    // ------------------------------------------------------------------

    @Test
    void upgradeDefaultsFillMobsWithoutOwnRequirements() {
        final SpawnerConfig config = parse(upgradeDoc(
                DEFAULTS_DOC, "upgrades: {}"));
        final SpawnerMob pig = config.group("organic").mob("pig");
        assertEquals(UpgradeRequirement.essence(com.coremc.core.essence.EssenceType.SLAYER, 10),
                find(pig, SpawnerVariant.ADVANCED, UpgradeRequirement.Type.ESSENCE));
        assertEquals(3, find(pig, SpawnerVariant.ADVANCED, UpgradeRequirement.Type.DROP).amount());
        assertEquals(25, find(pig, SpawnerVariant.ANCIENT, UpgradeRequirement.Type.ESSENCE).amount());
        assertEquals(75, find(pig, SpawnerVariant.MYTHIC, UpgradeRequirement.Type.ESSENCE).amount());
        assertNull(pig.upgradeRequirements(SpawnerVariant.NORMAL));
    }

    @Test
    void groupOverridesBeatTheDefaults() {
        final SpawnerConfig config = parse(upgradeDoc(
                DEFAULTS_DOC, "upgrades: {}",
                """
                    upgrade-overrides:
                      mythic:
                        - {type: money, amount: 9000}
                        - {type: kills, amount: 9000}
                        - {type: essence, essence: slayer, amount: 90}
                        - {type: drop, amount: 36}
                    """));
        final SpawnerMob pig = config.group("organic").mob("pig");
        // the group's mythic beats the global default...
        assertEquals(90, find(pig, SpawnerVariant.MYTHIC, UpgradeRequirement.Type.ESSENCE).amount());
        // ...while untouched tiers still fall back to the defaults
        assertEquals(10, find(pig, SpawnerVariant.ADVANCED, UpgradeRequirement.Type.ESSENCE).amount());
        assertEquals(25, find(pig, SpawnerVariant.ANCIENT, UpgradeRequirement.Type.ESSENCE).amount());
    }

    @Test
    void mobUpgradesBeatGroupOverridesAndDefaults() {
        final SpawnerConfig config = parse(upgradeDoc(
                DEFAULTS_DOC, """
                    upgrades:
                      mythic:
                        - {type: money, amount: 1111}
                        - {type: kills, amount: 1111}
                        - {type: essence, essence: slayer, amount: 111}
                        - {type: drop, amount: 44}
                    """,
                """
                    upgrade-overrides:
                      mythic:
                        - {type: money, amount: 9000}
                        - {type: kills, amount: 9000}
                        - {type: essence, essence: slayer, amount: 90}
                        - {type: drop, amount: 36}
                    """));
        final SpawnerMob pig = config.group("organic").mob("pig");
        assertEquals(111, find(pig, SpawnerVariant.MYTHIC, UpgradeRequirement.Type.ESSENCE).amount());
        // tiers the mob does not name still resolve through the chain
        assertEquals(25, find(pig, SpawnerVariant.ANCIENT, UpgradeRequirement.Type.ESSENCE).amount());
    }

    @Test
    void dropRequirementMayNameAnotherMob() {
        final SpawnerConfig config = parse(upgradeDoc(
                DEFAULTS_DOC, """
                    upgrades:
                      mythic:
                        - {type: money, amount: 1111}
                        - {type: kills, amount: 1111}
                        - {type: essence, essence: slayer, amount: 111}
                        - {type: drop, amount: 44, mob: cow}
                    """));
        final SpawnerMob pig = config.group("organic").mob("pig");
        assertEquals("cow", find(pig, SpawnerVariant.MYTHIC, UpgradeRequirement.Type.DROP).mobId());
    }

    @Test
    void incompleteUpgradeDefaultsAreRejected() {
        final String yaml = upgradeDoc(
                """
                    upgrade-defaults:
                      advanced:
                        - {type: money, amount: 100}
                        - {type: essence, essence: slayer, amount: 10}
                        - {type: drop, amount: 3}
                    """, "upgrades: {}");
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
        final String yaml = minimalGroups("organic", "item: HEART_OF_THE_SEA",
                        REQUIREMENTS_ALL_TIERS)
                .replace("costs: [750, 2500, 7500, 20000]", "costs: [750, 2500]");
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(yaml));
        assertTrue(error.getMessage().contains("costs"), error.getMessage());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static final String DEFAULTS_DOC = """
            upgrade-defaults:
              advanced:
                - {type: money, amount: 1000}
                - {type: kills, amount: 1000}
                - {type: essence, essence: slayer, amount: 10}
                - {type: drop, amount: 3}
              ancient:
                - {type: money, amount: 2500}
                - {type: kills, amount: 2500}
                - {type: essence, essence: slayer, amount: 25}
                - {type: drop, amount: 10}
              mythic:
                - {type: money, amount: 7500}
                - {type: kills, amount: 7500}
                - {type: essence, essence: slayer, amount: 75}
                - {type: drop, amount: 30}
            """;

    /** All three tiers as mob-level requirements, for the minimal document. */
    private static final String REQUIREMENTS_ALL_TIERS = """
        upgrades:
          advanced:
            - {type: money, amount: 100}
            - {type: kills, amount: 100}
            - {type: essence, essence: slayer, amount: 10}
            - {type: drop, amount: 1}
          ancient:
            - {type: money, amount: 200}
            - {type: kills, amount: 200}
            - {type: essence, essence: slayer, amount: 20}
            - {type: drop, amount: 2}
          mythic:
            - {type: money, amount: 300}
            - {type: kills, amount: 300}
            - {type: essence, essence: slayer, amount: 30}
            - {type: drop, amount: 3}
        """;

    private static UpgradeRequirement find(final SpawnerMob mob, final SpawnerVariant variant,
                                           final UpgradeRequirement.Type type) {
        for (final UpgradeRequirement requirement : mob.upgradeRequirements(variant)) {
            if (requirement.type() == type) {
                return requirement;
            }
        }
        return null;
    }

    /**
     * Minimal document with upgrade-defaults, an optional group-level
     * upgrade-overrides block and a chosen pig upgrades block, for the
     * progression-resolution tests. All inserted blocks are written at
     * zero indent and re-indented to their YAML nesting level here.
     */
    private static String upgradeDoc(final String upgradeDefaults, final String pigUpgrades) {
        return upgradeDoc(upgradeDefaults, pigUpgrades, "");
    }

    private static String upgradeDoc(final String upgradeDefaults, final String pigUpgrades,
                                     final String groupOverrides) {
        return minimalGroups("organic", "item: HEART_OF_THE_SEA", pigUpgrades)
                .replace("    relic: {item: HEART_OF_THE_SEA, name: \"Test Relic\"}",
                        "    relic: {item: HEART_OF_THE_SEA, name: \"Test Relic\"}\n"
                                + indented(groupOverrides, 4))
                .replace("\ngroups:", "\n" + upgradeDefaults + "\ngroups:");
    }

    /** Minimal-but-valid document with one group and one mob, for mutation tests. */
    private static String minimalGroups(final String groupId, final String relic,
                                        final String pigUpgrades) {
        return """
                settings:
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
                    relic: {%s, name: "Test Relic"}
                    mobs:
                      pig:
                        entity: PIG
                        name: "Pig"
                        drop: {item: BONE, name: "Pig Tusk"}
                        spawner-cost: 2500
                        unlock: {}
                %s""".formatted(groupId, relic, indented(pigUpgrades, 8));
    }

    /** Re-indents a zero-based YAML block by the given prefix (per line). */
    private static String indented(final String block, final int spaces) {
        if (block == null || block.isBlank()) {
            return "";
        }
        final String indent = " ".repeat(spaces);
        final StringBuilder out = new StringBuilder();
        for (final String line : block.stripTrailing().split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line.isBlank() ? "" : indent + line);
        }
        return out.toString();
    }
}