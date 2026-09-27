package com.coremc.core.season;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class SeasonConfigTest {

    @Test
    void shippedSeasonHasFiftyMeaningfulConfiguredLevels() throws Exception {
        final SeasonConfig config = parse(Files.readString(Path.of("src/main/resources/season.yml"), StandardCharsets.UTF_8));
        assertEquals(50, config.maxLevel());
        assertEquals(50, config.tiers().size());
        assertTrue(config.xpForLevel(10) < config.xpForLevel(30));
        assertTrue(config.xpForLevel(30) < config.xpForLevel(45));
        assertTrue(config.xpForLevel(45) < config.xpForLevel(50));
        assertFalse(config.premiumEnabled());
        assertEquals(3, new SeasonJourneyGui(new SeasonJourneyService(null, config,
                new YamlSeasonJourneyStore(Path.of("target/test-season-gui.yml"), java.util.logging.Logger.getLogger("test")),
                null, null, SeasonRewardRegistry.defaults(null), SeasonPremiumAccess.none(),
                null, java.util.logging.Logger.getLogger("test"), () -> config.season().startAt() + 1_000L)).maxPage());
    }

    @Test
    void parsesCurveSourcesAndRewards() throws Exception {
        final SeasonConfig config = parse(validYaml("season-one"));

        assertTrue(config.enabled());
        assertEquals("season-one", config.season().id());
        assertEquals(10, config.maxLevel());
        assertEquals(1, config.levelForXp(0));
        assertEquals(1, config.levelForXp(99));
        assertEquals(2, config.levelForXp(100));
        assertEquals(10, config.levelForXp(2_000));
        assertEquals(100, config.nextXpFor(0));
        assertEquals(900, config.maxXp());
        assertEquals(100L, config.sourceXp(SeasonXpSource.DAILY_QUEST));
        assertEquals(250L, config.sourceXp(SeasonXpSource.EVENT));
        assertFalse(config.premiumEnabled());
        assertNotNull(config.tier(1));
    }

    @Test
    void rejectsInvalidTimestampsAndNonMonotonicCurve() throws Exception {
        final String yaml = validYaml("bad")
                .replace("2026-09-27T00:00:00Z", "not-a-date")
                .replace("    3: 200", "    3: 100");
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> parse(yaml));
        assertTrue(thrown.getMessage().contains("season.start"));
        assertTrue(thrown.getMessage().contains("monotonically"));
    }

    @Test
    void rejectsMalformedSourcesAndRewards() throws Exception {
        final String yaml = validYaml("bad-source")
                .replace("  daily-quest: 100", "  daily-quest: 100\n  afk-standing: 1")
                .replace("          type: credits", "          type: best-in-slot-sword");
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> parse(yaml));
        assertTrue(thrown.getMessage().contains("afk-standing"));
        assertTrue(thrown.getMessage().contains("unsupported"));
    }

    static SeasonConfig parse(final String raw) throws Exception {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(raw);
        final SeasonConfig config = new SeasonConfig(null);
        config.parse(yaml);
        return config;
    }

    static String validYaml(final String seasonId) {
        return """
                season:
                  id: %s
                  name: "Test Season"
                  start: "2026-09-27T00:00:00Z"
                  end: "2026-11-15T00:00:00Z"
                levels:
                  max-level: 10
                  xp-required:
                    1: 0
                    2: 100
                    3: 200
                    4: 300
                    5: 400
                    6: 500
                    7: 600
                    8: 700
                    9: 800
                    10: 900
                sources:
                  daily-quest: 100
                  weekly-quest: 300
                  island-challenge: 250
                  island-milestone: 150
                  mastery-milestone: 150
                  core-unlock: 200
                  role-milestone: 200
                  omnitool-milestone: 200
                  mining-milestone: 100
                  farming-milestone: 100
                  fishing-milestone: 100
                  slayer-milestone: 100
                  generator-milestone: 100
                  event: 250
                  admin: 0
                premium:
                  enabled: false
                rewards:
                  levels:
                    1:
                      icon: CHEST
                      free:
                        credits:
                          type: credits
                          amount: 5
                    10:
                      icon: NETHER_STAR
                      free:
                        trophy:
                          type: trophy
                          amount: 1
                """.formatted(seasonId);
    }
}
