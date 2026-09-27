package com.coremc.core.quest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class QuestRewardIntegrationTest {

    @Test
    void unavailableCreditsAdapterKeepsRewardPending() {
        final QuestRewardIntegration credits = QuestRewardIntegration.unavailable("credits");
        final QuestConfig.RewardDef reward = new QuestConfig.RewardDef("credits", "credits", 25, "", "player");
        assertFalse(credits.available());
        assertEquals(QuestRewardIntegration.Result.UNAVAILABLE, credits.deliver(null, null, reward));
    }

    @Test
    void rewardKeysAreStableAcrossDisplayBalancing() {
        final QuestConfig.RewardDef reward = new QuestConfig.RewardDef("daily-credit", "credits", 25, "", "player");
        assertEquals("daily-credit", reward.key());
    }
}
