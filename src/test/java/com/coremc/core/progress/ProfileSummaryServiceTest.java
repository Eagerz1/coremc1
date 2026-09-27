package com.coremc.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.coremc.core.achievements.AchievementConfig;
import com.coremc.core.achievements.AchievementService;
import com.coremc.core.collections.CollectionConfig;
import com.coremc.core.collections.CollectionService;
import com.coremc.core.progress.adapter.ExternalSystems;
import com.coremc.core.progress.reward.PendingRewardStore;
import com.coremc.core.progress.reward.Reward;
import com.coremc.core.progress.reward.RewardService;
import com.coremc.core.progress.reward.RewardType;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The profile summary the placeholders read: a missing system reports
 * zero instead of breaking the line.
 */
class ProfileSummaryServiceTest {

    @TempDir
    Path folder;

    private static CollectionConfig collections() {
        return com.coremc.core.collections.CollectionConfigs.parse("""
                    collections:
                      cobblestone:
                        category: mining
                        action: mine_block
                        keys: [cobblestone]
                        milestones:
                          - amount: 10
                            rewards:
                              - {type: coins, amount: 100}
                      iron:
                        category: mining
                        action: mine_block
                        keys: [iron_ore]
                        milestones:
                          - amount: 10
                    """);
    }

    private static AchievementConfig achievements() {
        return com.coremc.core.achievements.AchievementConfigs.parse("""
                    achievements:
                      first_strike:
                        category: gathering
                        description: "Break one block."
                        action: mine_block
                        difficulty: common
                        requirement: 1
                    """);
    }

    @Test
    void everySystemMissingStillGivesAnEmptySummary() {
        final ProfileSummaryService summaries = new ProfileSummaryService(null, null, null);
        final ProfileSummaryService.Summary summary = summaries.of(UUID.randomUUID());
        assertEquals(0, summary.collectionPercent());
        assertEquals(0, summary.collectionsTotal());
        assertEquals(0, summary.achievementPoints());
        assertEquals(0, summary.achievementsTotal());
        assertEquals(0, summary.claimable());
        assertEquals(0, summary.held());
        assertEquals(0, summaries.of(null).collectionsTotal());
    }

    @Test
    void theSummaryAddsUpAcrossBothSystemsAndTheHeldRewards() {
        final PendingRewardStore pending =
                new PendingRewardStore(folder.resolve("pending.yml"), null);
        pending.load();
        final RewardService rewards =
                new RewardService(null, new ExternalSystems(), pending, null);
        final CollectionService collectionService =
                new CollectionService(collections(), null, rewards, null);
        final AchievementService achievementService =
                new AchievementService(achievements(), null, rewards, null);
        final UUID player = UUID.randomUUID();

        collectionService.add(player, "cobblestone", 10);
        achievementService.grant(player, "first_strike");
        pending.add(player, Reward.amount(RewardType.SKY_TOKENS, "", 5, ""), "test");

        final ProfileSummaryService.Summary summary =
                new ProfileSummaryService(collectionService, achievementService, rewards)
                        .of(player);
        assertEquals(50, summary.collectionPercent());
        assertEquals(1, summary.collectionsComplete());
        assertEquals(2, summary.collectionsTotal());
        assertEquals(5, summary.achievementPoints());
        assertEquals(1, summary.achievementsEarned());
        assertEquals(1, summary.achievementsTotal());
        assertEquals(1, summary.claimable(), "the cobblestone coins are waiting");
        assertEquals(1, summary.held());
    }
}
