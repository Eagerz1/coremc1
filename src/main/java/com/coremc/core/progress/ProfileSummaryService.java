package com.coremc.core.progress;

import com.coremc.core.achievements.AchievementService;
import com.coremc.core.collections.CollectionService;
import com.coremc.core.progress.reward.RewardService;
import java.util.UUID;

/**
 * One place to ask "how is this player doing?" across the permanent
 * progression systems.
 *
 * <p>Used by the placeholders today and by any future profile GUI or
 * guide page: each system stays independent, and callers never have
 * to know which of them happen to be enabled — a missing system
 * simply reports zero.</p>
 */
public final class ProfileSummaryService {

    /** A player's permanent progression at a glance. */
    public record Summary(int collectionPercent, int collectionsComplete, int collectionsTotal,
                          int achievementPoints, int achievementsEarned, int achievementsTotal,
                          int claimable, int held) {
    }

    private final CollectionService collections;
    private final AchievementService achievements;
    private final RewardService rewards;

    public ProfileSummaryService(final CollectionService collections,
                                 final AchievementService achievements,
                                 final RewardService rewards) {
        this.collections = collections;
        this.achievements = achievements;
        this.rewards = rewards;
    }

    /** The whole summary for one player. */
    public Summary of(final UUID player) {
        if (player == null) {
            return new Summary(0, 0, 0, 0, 0, 0, 0, 0);
        }
        final int collectionPercent = collections == null ? 0 : collections.totalPercent(player);
        final int complete = collections == null ? 0 : collections.completedTotal(player);
        final int total = collections == null ? 0 : collections.config().all().size();
        final int points = achievements == null ? 0 : achievements.points(player);
        final int earned = achievements == null ? 0 : achievements.earnedCount(player);
        final int achievementsTotal = achievements == null ? 0
                : achievements.config().all().size();
        final int claimable = (collections == null ? 0 : collections.claimableCount(player))
                + (achievements == null ? 0 : achievements.claimableCount(player));
        final int held = rewards == null ? 0 : rewards.pending().count(player);
        return new Summary(collectionPercent, complete, total, points, earned, achievementsTotal,
                claimable, held);
    }
}
