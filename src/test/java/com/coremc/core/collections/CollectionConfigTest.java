package com.coremc.core.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.progress.ProgressAction;
import com.coremc.core.progress.reward.Reward;
import com.coremc.core.progress.reward.RewardType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * collections.yml parsing and validation. The shipped file is parsed
 * here too, so a typo in the content fails the build rather than a
 * server start.
 */
class CollectionConfigTest {

    private static CollectionConfig parse(final String yaml) {
        final CollectionConfig config = new CollectionConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(parsed);
        return config;
    }

    private static CollectionConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/collections.yml"),
                StandardCharsets.UTF_8));
    }

    private static String entry(final String id, final String... lines) {
        final StringBuilder out = new StringBuilder("collections:\n  " + id + ":\n");
        for (final String line : lines) {
            out.append("    ").append(line).append('\n');
        }
        return out.toString();
    }

    private static final String[] MINIMAL = {
        "category: mining",
        "action: mine_block",
        "icon: COBBLESTONE",
        "keys: [cobblestone]",
        "milestones:",
        "  - {amount: 10}",
        "  - {amount: 100}",
    };

    // ------------------------------------------------------------------
    // the shipped content
    // ------------------------------------------------------------------

    @Test
    void theShippedFileParsesAndCoversEveryCategory() throws IOException {
        final CollectionConfig config = bundled();
        assertTrue(config.enabled());
        assertEquals(28, config.entriesPerPage());
        assertTrue(config.announceMilestones());
        assertTrue(config.all().size() >= 40, "the shipped file should be a real content set");
        assertEquals(CollectionCategory.values().length, config.categories().size(),
                "every category should ship with at least one entry");
    }

    @Test
    void shippedIdsAreUniqueAndMilestonesAscend() throws IOException {
        final Set<String> ids = new HashSet<>();
        for (final CollectionEntry entry : bundled().all()) {
            assertTrue(ids.add(entry.id()), "duplicate id " + entry.id());
            assertTrue(entry.tiers() > 0, entry.id() + " needs milestones");
            long previous = 0;
            for (final CollectionMilestone milestone : entry.milestones()) {
                assertTrue(milestone.amount() > previous,
                        entry.id() + " tier " + milestone.tier() + " does not ascend");
                previous = milestone.amount();
            }
        }
    }

    @Test
    void everyHiddenEntryHidesBehindAVagueHint() throws IOException {
        int hidden = 0;
        for (final CollectionEntry entry : bundled().all()) {
            if (entry.hidden()) {
                hidden++;
                assertFalse(entry.hint().isBlank(), entry.id() + " needs a hint");
                assertFalse(entry.hint().matches(".*\\d+%.*"),
                        entry.id() + " must not publish odds");
            }
        }
        assertTrue(hidden >= 3, "the shipped file should contain secret entries");
    }

    @Test
    void shippedRecipesPointAtRealCollectionsAndTiers() throws IOException {
        final CollectionConfig config = bundled();
        assertEquals(5, config.recipes().size());
        for (final UnlockableRecipe recipe : config.recipes()) {
            final CollectionEntry owner = config.byId(recipe.collectionId());
            assertNotNull(owner, recipe.id() + " points at a missing collection");
            assertTrue(recipe.tier() <= owner.tiers());
            assertFalse(recipe.ingredients().isEmpty());
            assertTrue(recipe.resultAmount() >= 1);
        }
        final UnlockableRecipe compressor = config.recipe("COBBLE_COMPRESSOR");
        assertNotNull(compressor);
        assertEquals("cobblestone", compressor.collectionId());
        assertEquals(3, compressor.tier());
        assertEquals(64, compressor.resultAmount());
        assertEquals("64x cobblestone", compressor.ingredients().get(0).text());
        assertEquals("64x stone", compressor.resultText());
    }

    @Test
    void cobblestoneMatchesTheDesignedCurve() throws IOException {
        final CollectionEntry cobble = bundled().byId("cobblestone");
        assertEquals(CollectionCategory.MINING, cobble.category());
        assertEquals(ProgressAction.MINE_BLOCK, cobble.action());
        assertEquals(5, cobble.tiers());
        assertEquals(100_000, cobble.finalAmount());
        assertTrue(cobble.keys().contains("stone"));
        assertFalse(cobble.hidden());
        final List<Reward> tierThree = cobble.milestones().get(2).rewards();
        assertEquals(RewardType.RECIPE, tierThree.get(0).type());
        assertEquals("cobble_compressor", tierThree.get(0).id());
    }

    // ------------------------------------------------------------------
    // lookups
    // ------------------------------------------------------------------

    @Test
    void lookupsAreCaseInsensitiveAndGroupedForTheHotPath() {
        final CollectionConfig config = parse(entry("Cobblestone", MINIMAL));
        assertNotNull(config.byId("COBBLESTONE"));
        assertNotNull(config.byId(" cobblestone "));
        assertNull(config.byId("nope"));
        assertNull(config.byId(null));
        assertEquals(1, config.byAction(ProgressAction.MINE_BLOCK).size());
        assertTrue(config.byAction(ProgressAction.FISH_CATCH).isEmpty());
        assertEquals(1, config.byCategory(CollectionCategory.MINING).size());
        assertTrue(config.byCategory(CollectionCategory.SEASONAL).isEmpty());
        assertEquals(List.of(CollectionCategory.MINING), config.categories());
    }

    @Test
    void aDisabledConfigIsHarmless() {
        final CollectionConfig config = CollectionConfig.disabled();
        assertFalse(config.enabled());
        assertTrue(config.all().isEmpty());
        assertTrue(config.categories().isEmpty());
        assertTrue(config.recipes().isEmpty());
        assertNull(config.byId("cobblestone"));
    }

    @Test
    void entriesPerPageIsClampedToTheGrid() {
        assertEquals(7, parse("settings:\n  entries-per-page: 1\n"
                + entry("a", MINIMAL)).entriesPerPage());
        assertEquals(45, parse("settings:\n  entries-per-page: 900\n"
                + entry("a", MINIMAL)).entriesPerPage());
    }

    // ------------------------------------------------------------------
    // validation — every problem must be loud
    // ------------------------------------------------------------------

    private static IllegalArgumentException broken(final String yaml) {
        return assertThrows(IllegalArgumentException.class, () -> parse(yaml));
    }

    @Test
    void aFileWithNoCollectionsIsRejected() {
        assertTrue(broken("settings:\n  entries-per-page: 28\n").getMessage()
                .contains("missing 'collections' mapping"));
    }

    @Test
    void unknownCategoriesActionsAndMaterialsAreRejected() {
        assertTrue(broken(entry("x", "category: wizardry", "action: mine_block",
                "milestones:", "  - {amount: 1}")).getMessage().contains("unknown category"));
        assertTrue(broken(entry("x", "category: mining", "action: cast_spell",
                "milestones:", "  - {amount: 1}")).getMessage().contains("unknown action"));
        assertTrue(broken(entry("x", "category: mining", "action: mine_block",
                "icon: UNOBTAINIUM", "milestones:", "  - {amount: 1}")).getMessage()
                .contains("unknown material"));
        assertTrue(broken(entry("x", "category: mining", "action: mine_block",
                "sources: [dreams]", "milestones:", "  - {amount: 1}")).getMessage()
                .contains("unknown source"));
    }

    @Test
    void milestonesMustExistAndAscend() {
        assertTrue(broken(entry("x", "category: mining", "action: mine_block")).getMessage()
                .contains("milestones is empty"));
        assertTrue(broken(entry("x", "category: mining", "action: mine_block",
                "milestones:", "  - {amount: 100}", "  - {amount: 50}")).getMessage()
                .contains("must be greater than the previous"));
        assertTrue(broken(entry("x", "category: mining", "action: mine_block",
                "milestones:", "  - {amount: 0}")).getMessage().contains("must be positive"));
        assertTrue(broken(entry("x", "category: mining", "action: mine_block",
                "milestones:", "  - {rewards: []}")).getMessage()
                .contains("amount is missing or not a number"));
    }

    @Test
    void oneSubjectMayOnlyFeedOneCollectionPerCategory() {
        final String yaml = "collections:\n"
                + "  cobblestone:\n    category: mining\n    action: mine_block\n"
                + "    keys: [cobblestone]\n    milestones:\n      - {amount: 10}\n"
                + "  cobble_again:\n    category: mining\n    action: mine_block\n"
                + "    keys: [cobblestone]\n    milestones:\n      - {amount: 10}\n";
        assertTrue(broken(yaml).getMessage().contains("already feeds another mining collection"));
    }

    @Test
    void theSameSubjectMayFeedADifferentCategory() {
        final String yaml = "collections:\n"
                + "  cobblestone:\n    category: mining\n    action: mine_block\n"
                + "    keys: [cobblestone]\n    milestones:\n      - {amount: 10}\n"
                + "  season_cobble:\n    category: seasonal\n    action: mine_block\n"
                + "    keys: [cobblestone]\n    milestones:\n      - {amount: 10}\n";
        assertEquals(2, parse(yaml).all().size());
    }

    @Test
    void hiddenEntriesWithoutAHintAreRejected() {
        assertTrue(broken(entry("x", "category: discoveries", "action: discovery",
                "hidden: true", "milestones:", "  - {amount: 1}")).getMessage()
                .contains("hidden entries need a 'hint'"));
    }

    @Test
    void rewardsAreValidatedDownToTheirTargets() {
        assertTrue(broken(entry("x", "category: mining", "action: mine_block", "milestones:",
                "  - {amount: 1, rewards: [{type: gold_stars}]}")).getMessage()
                .contains("unknown reward type"));
        assertTrue(broken(entry("x", "category: mining", "action: mine_block", "milestones:",
                "  - {amount: 1, rewards: [{type: coins}]}")).getMessage()
                .contains("need a positive amount"));
        assertTrue(broken(entry("x", "category: mining", "action: mine_block", "milestones:",
                "  - {amount: 1, rewards: [{type: title}]}")).getMessage()
                .contains("need an id"));
        assertTrue(broken(entry("x", "category: mining", "action: mine_block", "milestones:",
                "  - {amount: 1, rewards: [{type: item, id: NOT_A_THING, amount: 1}]}"))
                .getMessage().contains("unknown material"));
        assertTrue(broken(entry("x", "category: mining", "action: mine_block", "milestones:",
                "  - {amount: 1, rewards: [{type: recipe, id: ghost_recipe}]}")).getMessage()
                .contains("unknown recipe"));
        assertTrue(broken(entry("x", "category: mining", "action: mine_block", "milestones:",
                "  - {amount: 1, rewards: [{type: collection_tier, id: ghost}]}")).getMessage()
                .contains("unknown collection"));
    }

    @Test
    void recipesAreValidatedAgainstTheirCollection() {
        final String base = "recipes:\n  ghost:\n    icon: STONE\n    result: STONE\n"
                + "    requires: {collection: missing, tier: 1}\n    ingredients: [STONE:1]\n"
                + entry("x", MINIMAL);
        assertTrue(broken(base).getMessage().contains("unknown collection 'missing'"));

        final String tooHigh = "recipes:\n  deep:\n    icon: STONE\n    result: STONE\n"
                + "    requires: {collection: x, tier: 9}\n    ingredients: [STONE:1]\n"
                + entry("x", MINIMAL);
        assertTrue(broken(tooHigh).getMessage().contains("only has 2 tiers"));

        final String noIngredients = "recipes:\n  empty:\n    icon: STONE\n    result: STONE\n"
                + "    requires: {collection: x, tier: 1}\n    ingredients: []\n"
                + entry("x", MINIMAL);
        assertTrue(broken(noIngredients).getMessage().contains("ingredients is empty"));

        final String badAmount = "recipes:\n  bad:\n    icon: STONE\n    result: STONE\n"
                + "    requires: {collection: x, tier: 1}\n    ingredients: [STONE:zero]\n"
                + entry("x", MINIMAL);
        assertTrue(broken(badAmount).getMessage().contains("non-numeric amount"));
    }

    @Test
    void aBrokenFileLeavesNoHalfParsedState() {
        final CollectionConfig config = new CollectionConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString("collections:\n  good:\n    category: mining\n"
                    + "    action: mine_block\n    milestones:\n      - {amount: 10}\n"
                    + "  bad:\n    category: nonsense\n    action: mine_block\n"
                    + "    milestones:\n      - {amount: 10}\n");
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        assertThrows(IllegalArgumentException.class, () -> config.parse(parsed));
        assertTrue(config.all().isEmpty());
        assertTrue(config.recipes().isEmpty());
    }
}
