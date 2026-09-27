package com.coremc.core.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.progress.ProgressAction;
import com.coremc.core.progress.reward.Reward;
import com.coremc.core.progress.reward.RewardType;
import com.coremc.core.util.GuiText;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * The house style rules, enforced: & colour codes only, short lore,
 * ✔ / ✖ markers, and a locked thing never looking like an unlocked one.
 */
class CollectionLoreTest {

    private static CollectionEntry entry(final boolean hidden, final String hint) {
        return new CollectionEntry("cobblestone", CollectionCategory.MINING, "Cobblestone",
                Material.COBBLESTONE, ProgressAction.MINE_BLOCK, Set.of("cobblestone"), Set.of(),
                List.of(new CollectionMilestone(0, 10,
                                List.of(Reward.amount(RewardType.COINS, "", 500, ""))),
                        new CollectionMilestone(1, 100,
                                List.of(Reward.unlock(RewardType.TITLE, "stonebreaker", "")))),
                hidden, hint);
    }

    private static void houseStyle(final List<String> lore) {
        assertTrue(lore.size() <= 12, "lore must stay short, was " + lore.size());
        for (final String line : lore) {
            assertFalse(line.contains("<"), "no MiniMessage: " + line);
            assertFalse(line.contains("§"), "only & colour codes: " + line);
        }
    }

    @Test
    void titlesCarryTheCategoryColourAndTheRomanTier() {
        final CollectionEntry entry = entry(false, "");
        assertEquals("&b&l" + GuiText.caps("Cobblestone") + " &8[III]",
                CollectionLore.title(entry, 3));
        assertEquals("&b&l" + GuiText.caps("Cobblestone"), CollectionLore.title(entry, 0));
        assertTrue(CollectionLore.hiddenTitle().startsWith("&8&l"));
    }

    @Test
    void aHiddenEntryShowsAVagueHintAndNothingElse() {
        final List<String> lore = CollectionLore.hidden(entry(true, "Something in the deep."));
        houseStyle(lore);
        final String joined = String.join("\n", lore);
        assertTrue(joined.contains(GuiText.caps("Undiscovered")));
        assertTrue(joined.contains(GuiText.caps("Something in the deep.")));
        assertFalse(joined.contains("10"), "a hidden entry must not leak its milestones");
        assertFalse(joined.contains("%"), "a hidden entry must not leak odds");
    }

    @Test
    void anEntryLineShowsProgressAndCallsOutClaimableRewards() {
        final CollectionEntry entry = entry(false, "");
        final List<String> lore = CollectionLore.entry(entry, 50, 1);
        houseStyle(lore);
        final String joined = String.join("\n", lore);
        assertTrue(joined.contains(GuiText.caps("Collected")));
        assertTrue(joined.contains("50&7/&f100"));
        assertTrue(joined.contains(GuiText.TICK));
        assertTrue(joined.contains(GuiText.caps("1 reward to claim")));
        assertTrue(String.join("\n", CollectionLore.entry(entry, 50, 3))
                .contains(GuiText.caps("3 rewards to claim")));
    }

    @Test
    void aCompletedEntrySaysSoInGreen() {
        final List<String> lore = CollectionLore.entry(entry(false, ""), 100, 0);
        houseStyle(lore);
        assertTrue(String.join("\n", lore)
                .contains("&a" + GuiText.caps("Collection complete") + " &a" + GuiText.TICK));
    }

    @Test
    void lockedTiersNeverLookLikeUnlockedOnes() {
        final CollectionEntry entry = entry(false, "");
        final String locked = String.join("\n",
                CollectionLore.milestone(entry, entry.milestones().get(0), 5, false));
        final String claimable = String.join("\n",
                CollectionLore.milestone(entry, entry.milestones().get(0), 50, false));
        final String claimed = String.join("\n",
                CollectionLore.milestone(entry, entry.milestones().get(0), 50, true));
        final String automatic = String.join("\n",
                CollectionLore.milestone(entry, entry.milestones().get(1), 100, false));
        assertTrue(locked.contains("&c" + GuiText.caps("Locked") + " &c" + GuiText.CROSS));
        assertTrue(locked.contains(GuiText.caps("You have")));
        assertTrue(claimable.contains(GuiText.caps("Click to claim")));
        assertFalse(claimable.contains(GuiText.CROSS));
        assertTrue(claimed.contains("&8" + GuiText.caps("Already claimed")));
        assertTrue(automatic.contains("&a" + GuiText.caps("Unlocked") + " &a" + GuiText.TICK));
    }

    @Test
    void aTierWithoutRewardsSaysPrideOnly() {
        final CollectionEntry bare = new CollectionEntry("bare", CollectionCategory.MINING, "Bare",
                Material.STONE, ProgressAction.MINE_BLOCK, Set.of(), Set.of(),
                List.of(new CollectionMilestone(0, 10, List.of())), false, "");
        assertTrue(String.join("\n", CollectionLore.milestone(bare, bare.milestones().get(0), 10,
                false)).contains(GuiText.caps("No reward — pride only.")));
    }

    @Test
    void categoryAndPanelLoreAlwaysFitTheMenu() {
        houseStyle(CollectionLore.category(CollectionCategory.MINING, 10, 3, 42, 2));
        houseStyle(CollectionLore.category(CollectionCategory.MINING, 10, 3, 42, 0));
        houseStyle(CollectionLore.panel(42, 3, 10, 2, 1));
        houseStyle(CollectionLore.panel(0, 0, 10, 0, 0));
        assertTrue(String.join("\n", CollectionLore.panel(0, 0, 10, 0, 0))
                .contains(GuiText.caps("Nothing to claim right now")));
        assertTrue(String.join("\n", CollectionLore.panel(10, 1, 10, 0, 4))
                .contains(GuiText.caps("4 held safely for you")));
        assertTrue(String.join("\n", CollectionLore.panel(10, 1, 10, 1, 0))
                .contains(GuiText.caps("1 reward waiting")));
    }

    @Test
    void recipeLoreShowsTheRequirementAndTheLockState() {
        final UnlockableRecipe recipe = new UnlockableRecipe("cobble_compressor",
                "Cobble Compressor", Material.STONE, "cobblestone", 2,
                List.of(new UnlockableRecipe.Ingredient(Material.COBBLESTONE, 64)),
                Material.STONE, 64, "Compresses cobblestone.");
        final CollectionEntry entry = entry(false, "");
        final String locked = String.join("\n", CollectionLore.recipe(recipe, entry, 1, false));
        final String unlocked = String.join("\n", CollectionLore.recipe(recipe, entry, 2, true));
        houseStyle(CollectionLore.recipe(recipe, entry, 1, false));
        assertTrue(locked.contains(GuiText.CROSS));
        assertTrue(locked.contains(GuiText.caps("64x cobblestone")));
        assertTrue(locked.contains(GuiText.caps("Your tier")));
        assertTrue(unlocked.contains("&a" + GuiText.caps("Unlocked") + " &a" + GuiText.TICK));
        assertFalse(unlocked.contains(GuiText.caps("Your tier")));
        // a recipe whose collection went missing still names its requirement
        assertTrue(String.join("\n", CollectionLore.recipe(recipe, null, 0, false))
                .contains(GuiText.caps("cobblestone II")));
    }

    @Test
    void progressBarsAreAlwaysTwentyBlocksWide() {
        assertEquals(20, countBars(CollectionLore.bar(0.0)));
        assertEquals(20, countBars(CollectionLore.bar(0.5)));
        assertEquals(20, countBars(CollectionLore.bar(1.0)));
        assertEquals(20, countBars(CollectionLore.bar(5.0)));
        assertEquals(20, countBars(CollectionLore.bar(-1.0)));
        assertEquals(20, countBars(CollectionLore.bar(Double.NaN)));
        assertTrue(CollectionLore.bar(0.5).endsWith("&f50%"));
        assertTrue(CollectionLore.bar(1.0).startsWith("&a"));
        assertTrue(CollectionLore.bar(0.99).startsWith("&b"));
        assertTrue(CollectionLore.bar(0.999).endsWith("&f99%"));
    }

    private static int countBars(final String bar) {
        return (int) bar.chars().filter(character -> character == '\u25a0').count();
    }

    @Test
    void discoveryTimestampsReadAsPlainEnglish() {
        final long now = System.currentTimeMillis();
        assertEquals("unknown", CollectionLore.ago(0));
        assertEquals("just now", CollectionLore.ago(now));
        assertEquals("5m ago", CollectionLore.ago(now - 5 * 60_000L));
        assertEquals("2h ago", CollectionLore.ago(now - 2 * 3_600_000L));
        assertEquals("3d ago", CollectionLore.ago(now - 3 * 86_400_000L));
    }

    @Test
    void theDetailPanelPromisesPermanence() {
        final String lore = String.join("\n",
                CollectionLore.detail(entry(false, ""), 50, DiscoveryRecord.NONE));
        assertTrue(lore.contains(GuiText.caps("Season resets never touch it.")));
        assertTrue(lore.contains(GuiText.caps("Completion")));
        houseStyle(CollectionLore.detail(entry(false, ""), 50, DiscoveryRecord.NONE));
    }
}
