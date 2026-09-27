package com.coremc.core.achievements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.progress.ProgressAction;
import com.coremc.core.progress.reward.RewardType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * achievements.yml parsing and validation, including the shipped
 * content set.
 */
class AchievementConfigTest {

    private static AchievementConfig parse(final String yaml) {
        final AchievementConfig config = new AchievementConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(parsed);
        return config;
    }

    private static AchievementConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/achievements.yml"),
                StandardCharsets.UTF_8));
    }

    private static String one(final String id, final String... lines) {
        final StringBuilder out = new StringBuilder("achievements:\n  " + id + ":\n");
        for (final String line : lines) {
            out.append("    ").append(line).append('\n');
        }
        return out.toString();
    }

    private static final String[] MINIMAL = {
        "category: gathering",
        "action: mine_block",
        "difficulty: common",
        "requirement: 10",
    };

    // ------------------------------------------------------------------
    // the shipped content
    // ------------------------------------------------------------------

    @Test
    void theShippedFileParsesAndFillsEveryCategory() throws IOException {
        final AchievementConfig config = bundled();
        assertTrue(config.enabled());
        assertTrue(config.announce());
        assertTrue(config.broadcastRare());
        assertTrue(config.all().size() >= 30);
        assertEquals(AchievementCategory.values().length, config.categories().size());
        assertTrue(config.maxPoints() > 0);
    }

    @Test
    void shippedPointsFollowTheDifficultyTable() throws IOException {
        final AchievementConfig config = bundled();
        assertEquals(5, config.points(AchievementDifficulty.COMMON));
        assertEquals(15, config.points(AchievementDifficulty.RARE));
        assertEquals(30, config.points(AchievementDifficulty.EPIC));
        assertEquals(50, config.points(AchievementDifficulty.LEGENDARY));
        assertEquals(100, config.points(AchievementDifficulty.PRESTIGE));
        for (final Achievement achievement : config.all()) {
            assertTrue(achievement.points() > 0, achievement.id() + " must be worth something");
        }
    }

    @Test
    void shippedIdsAreUniqueAndEveryRequirementIsReachable() throws IOException {
        final Set<String> ids = new HashSet<>();
        for (final Achievement achievement : bundled().all()) {
            assertTrue(ids.add(achievement.id()), "duplicate " + achievement.id());
            assertTrue(achievement.requirement() >= 1);
            assertFalse(achievement.description().isBlank(),
                    achievement.id() + " needs a description");
            if (achievement.mode() == AchievementMode.UNIQUE && !achievement.keys().isEmpty()) {
                assertTrue(achievement.keys().size() >= achievement.requirement(),
                        achievement.id() + " cannot be earned");
            }
        }
    }

    @Test
    void shippedSecretsAndSeasonalsAreFlaggedCorrectly() throws IOException {
        final AchievementConfig config = bundled();
        int secrets = 0;
        for (final Achievement achievement : config.all()) {
            if (achievement.secret()) {
                secrets++;
            }
            if (achievement.category() == AchievementCategory.SEASONAL) {
                assertTrue(achievement.seasonal(),
                        achievement.id() + " must record its season");
            }
        }
        assertTrue(secrets >= 4, "the shipped file should contain secrets");
        assertNotNull(config.byId("the_deep_end"));
        assertTrue(config.byId("the_deep_end").secret());
        assertTrue(config.byId("season_champion").seasonal());
    }

    @Test
    void firstStrikeIsTheEntryPointAchievement() throws IOException {
        final Achievement first = bundled().byId("first_strike");
        assertEquals(AchievementCategory.GATHERING, first.category());
        assertEquals(AchievementDifficulty.COMMON, first.difficulty());
        assertEquals(ProgressAction.MINE_BLOCK, first.action());
        assertEquals(AchievementMode.TOTAL, first.mode());
        assertEquals(1, first.requirement());
        assertEquals(5, first.points());
        assertEquals(RewardType.COINS, first.rewards().get(0).type());
        assertTrue(first.hasManualReward());
    }

    // ------------------------------------------------------------------
    // lookups
    // ------------------------------------------------------------------

    @Test
    void lookupsAreCaseInsensitiveAndGrouped() {
        final AchievementConfig config = parse(one("First_Strike", MINIMAL));
        assertNotNull(config.byId("FIRST_STRIKE"));
        assertNotNull(config.byId(" first_strike "));
        assertNull(config.byId("ghost"));
        assertNull(config.byId(null));
        assertEquals(1, config.byAction(ProgressAction.MINE_BLOCK).size());
        assertTrue(config.byAction(ProgressAction.FISH_CATCH).isEmpty());
        assertEquals(1, config.byCategory(AchievementCategory.GATHERING).size());
        assertEquals(5, config.maxPoints());
    }

    @Test
    void aDisabledConfigIsHarmless() {
        final AchievementConfig config = AchievementConfig.disabled();
        assertFalse(config.enabled());
        assertTrue(config.all().isEmpty());
        assertTrue(config.categories().isEmpty());
        assertEquals(0, config.maxPoints());
        assertNull(config.byId("first_strike"));
        assertEquals(5, config.points(AchievementDifficulty.COMMON));
    }

    @Test
    void pointsCanBeOverriddenPerDifficultyAndPerAchievement() {
        final AchievementConfig config = parse("settings:\n  points:\n    common: 7\n"
                + one("a", MINIMAL));
        assertEquals(7, config.points(AchievementDifficulty.COMMON));
        assertEquals(7, config.byId("a").points());

        final AchievementConfig override = parse(one("a", "category: gathering",
                "action: mine_block", "difficulty: common", "requirement: 1", "points: 42"));
        assertEquals(42, override.byId("a").points());
    }

    @Test
    void seasonalCategoryImpliesASeasonStamp() {
        final AchievementConfig config = parse(one("s", "category: seasonal",
                "action: season_level", "difficulty: rare", "requirement: 5",
                "description: \"Reach season level 5.\""));
        assertTrue(config.byId("s").seasonal());
        final AchievementConfig plain = parse(one("g", MINIMAL));
        assertFalse(plain.byId("g").seasonal());
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    private static IllegalArgumentException broken(final String yaml) {
        return assertThrows(IllegalArgumentException.class, () -> parse(yaml));
    }

    @Test
    void aFileWithNoAchievementsIsRejected() {
        assertTrue(broken("settings:\n  announce: true\n").getMessage()
                .contains("missing 'achievements' mapping"));
    }

    @Test
    void unknownCategoriesDifficultiesActionsModesAndMaterialsAreRejected() {
        assertTrue(broken(one("a", "category: wizardry", "action: mine_block")).getMessage()
                .contains("unknown category"));
        assertTrue(broken(one("a", "category: gathering", "action: mine_block",
                "difficulty: mythic")).getMessage().contains("unknown difficulty"));
        assertTrue(broken(one("a", "category: gathering", "action: cast_spell")).getMessage()
                .contains("unknown action"));
        assertTrue(broken(one("a", "category: gathering", "action: mine_block",
                "mode: vibes")).getMessage().contains("unknown mode"));
        assertTrue(broken(one("a", "category: gathering", "action: mine_block",
                "icon: UNOBTAINIUM")).getMessage().contains("unknown material"));
        assertTrue(broken(one("a", "category: gathering", "action: mine_block",
                "sources: [dreams]")).getMessage().contains("unknown source"));
    }

    @Test
    void impossibleRequirementsAreRejected() {
        assertTrue(broken(one("a", "category: gathering", "action: mine_block",
                "requirement: 0")).getMessage().contains("requirement must be positive"));
        assertTrue(broken(one("a", "category: combat", "action: slayer_kill", "mode: unique",
                "keys: [zombie, skeleton]", "requirement: 10")).getMessage()
                .contains("impossible to earn"));
        assertTrue(broken("settings:\n  points:\n    common: -5\n" + one("a", MINIMAL))
                .getMessage().contains("cannot be negative"));
    }

    @Test
    void secretsStillNeedADescriptionForWhenTheyAreEarned() {
        assertTrue(broken(one("a", "category: secret", "action: mine_block", "secret: true",
                "requirement: 1")).getMessage().contains("secret achievements still need"));
    }

    @Test
    void rewardsAreValidatedLikeCollections() {
        assertTrue(broken(one("a", "category: gathering", "action: mine_block", "requirement: 1",
                "rewards:", "  - {type: gold_stars}")).getMessage()
                .contains("unknown reward type"));
        assertTrue(broken(one("a", "category: gathering", "action: mine_block", "requirement: 1",
                "rewards:", "  - {type: coins}")).getMessage().contains("need a positive amount"));
        assertTrue(broken(one("a", "category: gathering", "action: mine_block", "requirement: 1",
                "rewards:", "  - {type: title}")).getMessage().contains("need an id"));
        assertTrue(broken(one("a", "category: gathering", "action: mine_block", "requirement: 1",
                "rewards:", "  - {type: item, id: NOT_A_THING, amount: 1}")).getMessage()
                .contains("unknown material"));
    }

    @Test
    void aBrokenFileLeavesNoHalfParsedState() {
        final AchievementConfig config = new AchievementConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString("achievements:\n  good:\n    category: gathering\n"
                    + "    action: mine_block\n    requirement: 1\n"
                    + "  bad:\n    category: nonsense\n    action: mine_block\n");
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        assertThrows(IllegalArgumentException.class, () -> config.parse(parsed));
        assertTrue(config.all().isEmpty());
        assertEquals(0, config.maxPoints());
    }
}
