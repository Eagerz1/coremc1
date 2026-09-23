package com.coremc.core.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * ranks.yml parsing: the shipped ladder loads with the exact perk
 * numbers, the ladder must only improve as it climbs, broken files
 * throw (and never half-work), and the disabled fallback leaves no
 * ranks and no perks.
 */
class RankConfigTest {

    private static final String LADDER = String.join("\n",
            "ranks:",
            "  core:",
            "    name: \"Core\"",
            "    price: 75000",
            "    money-multiplier: 1.05",
            "    season-money: 100000",
            "    river-keys: 2",
            "    perks: {chat-color: true, gradients: false, bold: false}",
            "  core-plus:",
            "    name: \"Core+\"",
            "    price: 250000",
            "    money-multiplier: 1.20",
            "    season-money: 250000",
            "    river-keys: 5",
            "    perks: {chat-color: true, gradients: true, bold: false}",
            "  core-plus-plus:",
            "    name: \"Core++\"",
            "    price: 750000",
            "    money-multiplier: 1.50",
            "    season-money: 500000",
            "    river-keys: 12",
            "    perks: {chat-color: true, gradients: true, bold: true}");

    @Test
    void shippedDefaultsParseCleanly() throws Exception {
        final RankConfig config = parse(
                java.nio.file.Files.readString(Path.of("src/main/resources/ranks.yml")));
        assertTrue(config.enabled());
        assertEquals(3, config.ranks().size(), "Core, Core+, Core++");
        assertEquals("core", config.ranks().get(0).id());
        assertEquals("core-plus", config.ranks().get(1).id());
        assertEquals("core-plus-plus", config.ranks().get(2).id(), "worst to best order");
    }

    @Test
    void ladderNumbersMatchTheSpec() {
        final RankConfig config = parse(LADDER);
        final RankConfig.RankDef core = config.byId("core");
        final RankConfig.RankDef plus = config.byId("core-plus");
        final RankConfig.RankDef plusPlus = config.byId("core-plus-plus");

        assertEquals(1.05, core.moneyMultiplier(), 0.0001, "Core: 0.05x more money");
        assertEquals(100000, core.seasonMoney(), 0.0001, "Core: 100k each season");
        assertEquals(2, core.riverKeys(), "Core: 2 River keys");
        assertTrue(core.chatColor(), "Core: dye-colour chat");
        assertFalse(core.gradients(), "Core: no gradients");
        assertFalse(core.bold(), "Core: no bold");

        assertTrue(plus.gradients(), "Core+: gradients");
        assertFalse(plus.bold(), "Core+: no bold");
        assertTrue(plusPlus.gradients() && plusPlus.bold(), "Core++: gradients + bold");

        assertTrue(plus.moneyMultiplier() > core.moneyMultiplier(), "better multiplier");
        assertTrue(plus.seasonMoney() > core.seasonMoney(), "better payout");
        assertTrue(plus.riverKeys() > core.riverKeys(), "more keys");
        assertTrue(plusPlus.moneyMultiplier() > plus.moneyMultiplier(), "way better at the top");
    }

    @Test
    void nextOfWalksTheLadderAndStopsAtTheTop() {
        final RankConfig config = parse(LADDER);
        assertEquals("core", config.first().id());
        assertEquals("core-plus", config.nextOf(config.byId("core")).id());
        assertEquals("core-plus-plus", config.nextOf(config.byId("core-plus")).id());
        assertNull(config.nextOf(config.byId("core-plus-plus")), "nothing above Core++");
        assertEquals(1, config.indexOf(config.byId("core-plus")));
        assertNull(config.byId("nope"), "unknown ids resolve to null");
    }

    @Test
    void pricesAreTheUpgradeSteps() {
        final RankConfig config = parse(LADDER);
        assertEquals(75000, config.byId("core").price(), 0.0001);
        assertEquals(250000, config.byId("core-plus").price(), 0.0001);
        assertEquals(750000, config.byId("core-plus-plus").price(), 0.0001);
    }

    @Test
    void perksSummaryListsWhatTheRankUnlocks() {
        final RankConfig config = parse(LADDER);
        assertTrue(config.byId("core").perksSummary().contains("/fly"));
        assertTrue(config.byId("core").perksSummary().contains("chat colours"));
        assertFalse(config.byId("core").perksSummary().contains("gradients"));
        assertTrue(config.byId("core-plus-plus").perksSummary().contains("bold"));
    }

    @Test
    void multiplierBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(LADDER.replace("money-multiplier: 1.05", "money-multiplier: 0.9")));
    }

    @Test
    void negativeNumbersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(LADDER.replace("price: 75000", "price: -1")));
        assertThrows(IllegalArgumentException.class,
                () -> parse(LADDER.replace("season-money: 100000", "season-money: -5")));
        assertThrows(IllegalArgumentException.class,
                () -> parse(LADDER.replace("river-keys: 2", "river-keys: -2")));
    }

    @Test
    void aLadderThatGetsWorseIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(LADDER.replace("money-multiplier: 1.50", "money-multiplier: 1.10")),
                "top multiplier below Core+ is broken");
        assertThrows(IllegalArgumentException.class,
                () -> parse(LADDER.replace("season-money: 250000", "season-money: 50000")),
                "Core+ paying less than Core is broken");
        assertThrows(IllegalArgumentException.class,
                () -> parse(LADDER.replace("river-keys: 5", "river-keys: 1")),
                "fewer keys on a higher rank is broken");
    }

    @Test
    void missingOrEmptyRanksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("other: 1"));
        assertThrows(IllegalArgumentException.class, () -> parse("ranks: {}"));
    }

    @Test
    void disabledFallbackHasNoRanksAndNoPerks() {
        final RankConfig config = RankConfig.disabled();
        assertFalse(config.enabled());
        assertTrue(config.ranks().isEmpty());
        assertNull(config.first());
    }

    private RankConfig parse(final String yaml) {
        final RankConfig config = new RankConfig(null);
        final YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.loadFromString(yaml);
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(loaded);
        return config;
    }
}
