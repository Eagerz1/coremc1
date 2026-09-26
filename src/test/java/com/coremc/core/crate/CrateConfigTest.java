package com.coremc.core.crate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.reward.RewardDef;
import com.coremc.core.reward.RewardType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Crate config validation: the shipped crates.yml is parsed here too,
 * so a typo in the shipped keys or reward tables fails the build
 * instead of corrupting purchases on a live server.
 */
class CrateConfigTest {

    private static CrateConfig parse(final String yaml, final Set<String> lootboxIds) {
        final CrateConfig config = new CrateConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(parsed, lootboxIds);
        return config;
    }

    private static CrateConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/crates.yml"),
                StandardCharsets.UTF_8), Set.of("core", "monthly", "seasonal"));
    }

    // ------------------------------------------------------------------
    // the shipped file
    // ------------------------------------------------------------------

    @Test
    void bundledConfigShipsExactlyTheFiveKeys() throws IOException {
        final CrateConfig config = bundled();
        final List<KeyDef> keys = config.keys();
        assertEquals(5, keys.size());
        assertEquals(List.of("vote", "river", "sky", "crimson", "boost"),
                keys.stream().map(KeyDef::id).toList());
        assertEquals(75, config.key("vote").price());
        assertEquals(125, config.key("river").price());
        assertEquals(250, config.key("sky").price());
        assertEquals(400, config.key("crimson").price());
        assertEquals(300, config.key("boost").price());
        assertEquals("&a&lVote Key", config.key("vote").name());
        assertEquals("&b&lRiver Key", config.key("river").name());
        assertEquals("&9&lSky Key", config.key("sky").name());
        assertEquals("&c&lCrimson Key", config.key("crimson").name());
        assertEquals("&d&lBoost Key", config.key("boost").name());
    }

    @Test
    void bundledConfigBindsEveryCrateToItsKey() throws IOException {
        final CrateConfig config = bundled();
        assertEquals(5, config.crates().size());
        for (final CrateDef crate : config.crates()) {
            assertNotNull(config.key(crate.keyId()), crate.id());
            assertEquals(crate.id(), config.crateForKey(crate.keyId()).id());
            assertTrue(crate.rewards().totalWeight() > 0);
        }
    }

    @Test
    void bundledVoteCrateMatchesTheSpec() throws IOException {
        final CrateDef vote = bundled().crate("vote");
        final List<RewardDef> pool = vote.pool();
        // money 5k-15k, tokens 500-1500 + role xp, booster, tag
        final RewardDef money = pool.stream()
                .filter(def -> def.type() == RewardType.MONEY).findFirst().orElseThrow();
        assertEquals(5000, money.min());
        assertEquals(15000, money.max());
        final RewardDef tokens = pool.stream()
                .filter(def -> def.type() == RewardType.SKY_TOKENS).findFirst().orElseThrow();
        assertEquals(500, tokens.min());
        assertEquals(1500, tokens.max());
        assertEquals(5, pool.size());
    }

    @Test
    void bundledPoolsOnlyReferenceExistingKeysAndLootboxes() throws IOException {
        final CrateConfig config = bundled();
        for (final CrateDef crate : config.crates()) {
            for (final RewardDef def : crate.pool()) {
                if (def.type() == RewardType.KEY) {
                    assertNotNull(config.key(def.id()), crate.id() + " -> " + def.id());
                }
                if (def.type() == RewardType.LOOTBOX) {
                    assertTrue(Set.of("core", "monthly", "seasonal").contains(def.id()));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // validation is loud
    // ------------------------------------------------------------------

    private static final String VALID = """
            keys:
              vote:
                name: "&a&lVote Key"
                material: TRIPWIRE_HOOK
                price: 75
            crates:
              vote:
                name: "&a&lVote Crate"
                display-material: CHEST
                key: vote
                rewards:
                  money:
                    type: money
                    display: "&aMoney"
                    min: 100
                    max: 200
                    weight: 10
            """;

    @Test
    void minimalValidConfigParses() {
        final CrateConfig config = parse(VALID, null);
        assertEquals(1, config.keys().size());
        assertEquals(1, config.crates().size());
        assertNull(config.key("nope"));
    }

    @Test
    void unknownKeyMaterialFailsLoudly() {
        final String broken = VALID.replace("TRIPWIRE_HOOK", "NOT_A_MATERIAL");
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(broken, null));
        assertTrue(error.getMessage().contains("material"));
    }

    @Test
    void crateWithUnknownKeyFailsLoudly() {
        final String broken = VALID.replace("key: vote", "key: missing");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse(broken, null))
                .getMessage().contains("unknown key"));
    }

    @Test
    void nonPositiveWeightFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("weight: 10", "weight: 0"), null));
        assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("weight: 10", "weight: -4"), null));
    }

    @Test
    void badAmountRangeFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("max: 200", "max: 50"), null));
    }

    @Test
    void unknownRewardTypeFailsLoudly() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("type: money", "type: diamonds"), null))
                .getMessage().contains("unknown type"));
    }

    @Test
    void unknownLootboxReferenceFailsWhenIdsAreKnown() {
        final String withBox = VALID.replace("""
                  money:
                    type: money
                    display: "&aMoney"
                    min: 100
                    max: 200
                    weight: 10
            """, """
                  box:
                    type: lootbox
                    id: nope
                    display: "&bBox"
                    amount: 1
                    weight: 10
            """);
        assertThrows(IllegalArgumentException.class, () -> parse(withBox, Set.of("core")));
    }

    @Test
    void negativePriceFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("price: 75", "price: -1"), null));
    }

    @Test
    void missingSectionsFailLoudly() {
        assertThrows(IllegalArgumentException.class, () -> parse("keys: {}\ncrates: {}", null));
        assertThrows(IllegalArgumentException.class, () -> parse("nothing: here", null));
    }

    @Test
    void disabledConfigIsEmptyAndSafe() {
        final CrateConfig disabled = CrateConfig.disabled();
        assertEquals(false, disabled.enabled());
        assertTrue(disabled.keys().isEmpty());
        assertTrue(disabled.crates().isEmpty());
        assertNull(disabled.crateForKey("vote"));
    }
}
