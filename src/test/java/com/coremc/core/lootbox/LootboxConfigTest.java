package com.coremc.core.lootbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.reward.RewardDef;
import com.coremc.core.reward.RewardType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Lootbox config validation: the shipped lootboxes.yml must always
 * parse (three boxes, correct prices, both pools valid), and broken
 * configs must fail loudly instead of corrupting purchases.
 */
class LootboxConfigTest {

    private static final Set<String> KEYS = Set.of("vote", "river", "sky", "crimson", "boost");

    private static LootboxConfig parse(final String yaml, final Set<String> keyIds) {
        final LootboxConfig config = new LootboxConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(parsed, keyIds);
        return config;
    }

    private static LootboxConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/lootboxes.yml"),
                StandardCharsets.UTF_8), KEYS);
    }

    @Test
    void bundledConfigShipsTheThreeStoreLootboxes() throws IOException {
        final LootboxConfig config = bundled();
        assertEquals(3, config.all().size());
        assertEquals(Set.of("core", "monthly", "seasonal"), config.ids());
        assertEquals(500, config.byId("core").price());
        assertEquals(1000, config.byId("monthly").price());
        assertEquals(1500, config.byId("seasonal").price());
        assertEquals("&b&lCore Lootbox", config.byId("core").name());
        assertEquals("&d&lMonthly Lootbox", config.byId("monthly").name());
        assertEquals("&6&lSeasonal Lootbox", config.byId("seasonal").name());
    }

    @Test
    void everyBoxPromisesEightPlusOne() {
        assertEquals(8, LootboxDef.NORMAL_REWARDS);
    }

    @Test
    void bundledPoolsAreValidAndCrossReferenced() throws IOException {
        final LootboxConfig config = bundled();
        for (final LootboxDef box : config.all()) {
            assertTrue(box.normalPool().size() >= 5, box.id() + " normal pool too small");
            assertTrue(box.rarePool().size() >= 3, box.id() + " rare pool too small");
            assertTrue(box.categories().size() >= 3, box.id() + " lore categories missing");
            for (final RewardDef def : box.normalPool()) {
                if (def.type() == RewardType.KEY) {
                    assertTrue(KEYS.contains(def.id()), box.id() + " -> " + def.id());
                }
            }
            // the rare pool is actually rare: every entry rare or better
            for (final RewardDef def : box.rarePool()) {
                assertTrue(def.rare() || def.rarity().equals("epic")
                        || def.rarity().equals("legendary"), box.id() + " rare pool has "
                        + def.id() + " (" + def.rarity() + ")");
            }
        }
    }

    // ------------------------------------------------------------------
    // validation is loud
    // ------------------------------------------------------------------

    private static final String VALID = """
            lootboxes:
              core:
                name: "&b&lCore Lootbox"
                price: 500
                categories: ["Money", "Keys", "Materials"]
                normal-rewards:
                  money:
                    type: money
                    display: "&aMoney"
                    min: 100
                    max: 200
                    weight: 10
                rare-rewards:
                  crimson:
                    type: key
                    id: crimson
                    display: "&cCrimson Key"
                    rarity: rare
                    amount: 1
                    weight: 5
            """;

    @Test
    void minimalValidConfigParses() {
        final LootboxConfig config = parse(VALID, KEYS);
        assertNotNull(config.byId("core"));
        assertEquals(500, config.byId("core").price());
    }

    @Test
    void boxesMayPayOtherBoxes() {
        final String twoBoxes = VALID + """
              monthly:
                name: "&d&lMonthly Lootbox"
                price: 1000
                categories: ["Stuff"]
                normal-rewards:
                  corebox:
                    type: lootbox
                    id: core
                    display: "&bCore Lootbox"
                    amount: 1
                    weight: 4
                rare-rewards:
                  crimson:
                    type: key
                    id: crimson
                    display: "&cCrimson Key"
                    rarity: rare
                    amount: 1
                    weight: 5
            """;
        assertEquals(2, parse(twoBoxes, KEYS).all().size());
    }

    @Test
    void unknownKeyReferenceFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("id: crimson", "id: nope"), KEYS));
    }

    @Test
    void missingPriceOrNameFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("price: 500", "price: -2"), KEYS));
        assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("name: \"&b&lCore Lootbox\"", "name: \"\""), KEYS));
    }

    @Test
    void missingPoolsFailLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("rare-rewards", "other-rewards"), KEYS));
        assertThrows(IllegalArgumentException.class, () -> parse("lootboxes: {}", KEYS));
        assertThrows(IllegalArgumentException.class, () -> parse("something: else", KEYS));
    }

    @Test
    void disabledConfigIsEmptyAndSafe() {
        final LootboxConfig disabled = LootboxConfig.disabled();
        assertEquals(false, disabled.enabled());
        assertTrue(disabled.all().isEmpty());
    }
}
