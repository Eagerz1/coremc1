package com.coremc.core.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class YamlQuestStoreTest {

    @TempDir
    Path temp;

    @Test
    void savesAndRestoresPlayerAndIslandQuestState() throws Exception {
        final UUID player = UUID.randomUUID();
        final UUID island = UUID.randomUUID();
        final QuestState.PlayerState playerState = new QuestState.PlayerState(player);
        playerState.setDailyResetAt(123L);
        playerState.setWeeklyResetAt(456L);
        playerState.setStreak(3);
        playerState.setOnboardingShown(true);
        playerState.setOnboardingStep("complete-daily");
        final QuestState.Assignment daily = new QuestState.Assignment("daily-farm");
        daily.setProgress("main", 7L);
        daily.setCompleted(true, 99L);
        daily.deliveredRewards().add("sky-tokens::150:player");
        playerState.daily().put(daily.templateId(), daily);

        final QuestState.IslandChallenges islandState = new QuestState.IslandChallenges(island);
        islandState.setResetAt(789L);
        final QuestState.Assignment challenge = new QuestState.Assignment("island-harvest");
        challenge.setProgress("main", 11L);
        islandState.challenges().put(challenge.templateId(), challenge);
        islandState.contributors("island-harvest").put(player, 11L);
        islandState.claimedBy("island-harvest").add(player);
        islandState.islandDelivered("island-harvest").add("sky-tokens::500:island-once");

        final YamlQuestStore store = new YamlQuestStore(temp.resolve("quests.yml"), Logger.getLogger("test"));
        store.save(Map.of(player, playerState), Map.of(island, islandState));

        final YamlQuestStore.Data loaded = store.load();
        assertEquals(123L, loaded.players().get(player).dailyResetAt());
        assertEquals(3, loaded.players().get(player).streak());
        assertEquals("complete-daily", loaded.players().get(player).onboardingStep());
        assertEquals(7L, loaded.players().get(player).daily().get("daily-farm").progress("main"));
        assertTrue(loaded.players().get(player).daily().get("daily-farm").completed());
        assertEquals(789L, loaded.islands().get(island).resetAt());
        assertEquals(11L, loaded.islands().get(island).contributors("island-harvest").get(player));
        assertTrue(loaded.islands().get(island).claimedBy("island-harvest").contains(player));
        assertTrue(loaded.islands().get(island).islandDelivered("island-harvest")
                .contains("sky-tokens::500:island-once"));
    }
}
