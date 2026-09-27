package com.coremc.core.achievements;

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

/** Achievement menu wording and the earned / locked / secret split. */
class AchievementLoreTest {

    private static Achievement achievement(final AchievementDifficulty difficulty,
                                           final boolean secret, final boolean seasonal,
                                           final List<Reward> rewards) {
        return new Achievement("quarry_hand", AchievementCategory.GATHERING, "Quarry Hand",
                "Mine 100 blocks.", Material.STONE_PICKAXE, difficulty, difficulty.defaultPoints(),
                ProgressAction.MINE_BLOCK, Set.of(), Set.of(), AchievementMode.TOTAL, 100, secret,
                seasonal, rewards);
    }

    private static void houseStyle(final List<String> lore) {
        assertTrue(lore.size() <= 12, "lore must stay short, was " + lore.size());
        for (final String line : lore) {
            assertFalse(line.contains("<"), "no MiniMessage: " + line);
            assertFalse(line.contains("§"), "only & colour codes: " + line);
        }
    }

    @Test
    void titlesTurnGreenOnceEarned() {
        final Achievement achievement = achievement(AchievementDifficulty.RARE, false, false,
                List.of());
        assertEquals("&9&l" + GuiText.caps("Quarry Hand"),
                AchievementLore.title(achievement, false));
        assertEquals("&a&l" + GuiText.caps("Quarry Hand"),
                AchievementLore.title(achievement, true));
        assertTrue(AchievementLore.secretTitle().startsWith("&8&l"));
    }

    @Test
    void anUnearnedSecretGivesNothingAway() {
        final List<String> lore = AchievementLore.secret();
        houseStyle(lore);
        final String joined = String.join("\n", lore);
        assertTrue(joined.contains(GuiText.caps("Hidden until earned")));
        assertFalse(joined.contains("100"));
        assertFalse(joined.contains("%"));
    }

    @Test
    void anUnearnedAchievementShowsProgressAndACrossNeverATick() {
        final List<String> lore = AchievementLore.achievement(
                achievement(AchievementDifficulty.RARE, false, false, List.of()), 40, false, false,
                -1);
        houseStyle(lore);
        final String joined = String.join("\n", lore);
        assertTrue(joined.contains("40&7/&f100"));
        assertTrue(joined.contains("&c" + GuiText.caps("Not earned yet") + " &c" + GuiText.CROSS));
        assertFalse(joined.contains(GuiText.TICK));
    }

    @Test
    void anEarnedAchievementShowsATickAndItsSeason() {
        final Achievement seasonal = achievement(AchievementDifficulty.PRESTIGE, false, true,
                List.of());
        final String stamped = String.join("\n",
                AchievementLore.achievement(seasonal, 100, true, false, 3));
        assertTrue(stamped.contains("&a" + GuiText.caps("Earned") + " &a" + GuiText.TICK));
        assertTrue(stamped.contains(GuiText.caps("Season") + ": &63"));
        final String noSeason = String.join("\n",
                AchievementLore.achievement(seasonal, 100, true, false, -1));
        assertFalse(noSeason.contains(GuiText.caps("Season") + ":"));
    }

    @Test
    void claimableEarnedAndClaimedAllLookDifferent() {
        final Achievement paid = achievement(AchievementDifficulty.COMMON, false, false,
                List.of(Reward.amount(RewardType.COINS, "", 1_000, "")));
        final String claimable = String.join("\n",
                AchievementLore.achievement(paid, 100, true, true, -1));
        final String claimed = String.join("\n",
                AchievementLore.achievement(paid, 100, true, false, -1));
        assertTrue(claimable.contains(GuiText.caps("Click to claim")));
        assertTrue(claimed.contains("&8" + GuiText.caps("Already claimed")));
        assertTrue(claimable.contains(GuiText.money(1_000)));
    }

    @Test
    void categoryAndPanelLoreFitTheMenuAndNameThePrestigeRule() {
        houseStyle(AchievementLore.category(AchievementCategory.GATHERING, 10, 4, 60, 2));
        houseStyle(AchievementLore.category(AchievementCategory.GATHERING, 10, 4, 60, 0));
        houseStyle(AchievementLore.panel(120, 400, 8, 30, 2, 1));
        final String panel = String.join("\n", AchievementLore.panel(120, 400, 8, 30, 0, 0));
        assertTrue(panel.contains(GuiText.caps("Points are prestige, not currency.")));
        assertTrue(panel.contains(GuiText.caps("Nothing to claim right now")));
        assertTrue(String.join("\n", AchievementLore.panel(0, 0, 0, 0, 1, 2))
                .contains(GuiText.caps("2 held safely for you")));
    }

    @Test
    void fractionsNeverDivideByZeroOrRunPastOne() {
        assertEquals(0.0, AchievementLore.fraction(10, 0));
        assertEquals(0.0, AchievementLore.fraction(-5, 100));
        assertEquals(0.5, AchievementLore.fraction(50, 100), 1e-9);
        assertEquals(1.0, AchievementLore.fraction(500, 100));
    }
}
