package com.coremc.core.quest;

import static org.junit.jupiter.api.Assertions.*;

import com.coremc.core.island.Island;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuestServiceTest {

    @TempDir
    Path temp;

    @Test
    void rotatesWithAbsoluteTimestampsAndSkipsUnavailableSystems() throws Exception {
        final QuestConfig config = config();
        final AtomicLong clock = new AtomicLong(1_000L);
        final QuestService service = service(config, clock);
        service.load();
        final UUID player = UUID.randomUUID();
        final Island island = island(player);

        service.ensure(player, island);
        final QuestState.PlayerState state = service.playerState(player);
        assertEquals(86_400_000L, state.dailyResetAt());
        assertEquals(604_800_000L, state.weeklyResetAt());
        assertTrue(state.daily().containsKey("daily-farm"));
        assertFalse(state.daily().containsKey("daily-mining-cube"));

        clock.set(86_400_001L);
        service.ensure(player, island);
        assertEquals(172_800_000L, state.dailyResetAt());
    }

    @Test
    void dailyCompletionAdvancesStreakAndWeeklyDailyCounter() throws Exception {
        final QuestConfig config = config();
        final AtomicLong clock = new AtomicLong(1_000L);
        final QuestService service = service(config, clock);
        service.load();
        final UUID player = UUID.randomUUID();
        final Island island = island(player);
        service.ensure(player, island);

        service.recordAction(new QuestAction(player, island, "crop-harvested", 1L, Map.of()));
        assertFalse(service.playerState(player).daily().get("daily-farm").completed());
        service.recordAction(new QuestAction(player, island, "crop-harvested", 1L, Map.of()));

        assertTrue(service.playerState(player).daily().get("daily-farm").completed());
        assertEquals(1, service.playerState(player).streak());
        assertEquals(1, service.playerState(player).weekly().get("weekly-dailies").progress("main"));
    }

    @Test
    void islandChallengesAreSharedAndTrackContributors() throws Exception {
        final QuestConfig config = config();
        final AtomicLong clock = new AtomicLong(1_000L);
        final QuestService service = service(config, clock);
        service.load();
        final UUID owner = UUID.randomUUID();
        final UUID member = UUID.randomUUID();
        final Island island = island(owner);
        island.addMember(member);
        service.ensure(owner, island);

        service.recordAction(new QuestAction(owner, island, "crop-harvested", 1L, Map.of()));
        service.recordAction(new QuestAction(member, island, "crop-harvested", 2L, Map.of()));

        final QuestState.IslandChallenges state = service.islandState(island);
        assertTrue(state.challenges().get("island-harvest").completed());
        assertEquals(1L, state.contributors("island-harvest").get(owner));
        assertEquals(2L, state.contributors("island-harvest").get(member));
    }

    private QuestService service(final QuestConfig config, final AtomicLong clock) {
        final QuestIntegrationRegistry integrations = new QuestIntegrationRegistry()
                .system("farming", true)
                .system("progression", true)
                .system("events", true)
                .reward(QuestRewardIntegration.unavailable("credits"))
                .reward(QuestRewardIntegration.unavailable("sky-tokens"));
        return new QuestService(null, config,
                new YamlQuestStore(temp.resolve("quests-data.yml"), Logger.getLogger("test")),
                null, null, integrations, null, Logger.getLogger("test"), clock::get);
    }

    private QuestConfig config() throws Exception {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                settings:
                  daily: {count: 3, reset-hours: 24}
                  weekly: {count: 3, reset-hours: 168}
                  island-challenges: {count: 1, reset-hours: 168}
                  streak: {enabled: true}
                templates:
                  daily-farm:
                    type: daily
                    weight: 10
                    rewards: {tokens: {type: sky-tokens, amount: 1}}
                    objective: {action: crop-harvested, target: 2}
                  daily-event:
                    type: daily
                    weight: 10
                    rewards: {tokens: {type: sky-tokens, amount: 1}}
                    objective: {action: hourly-event-participation, target: 1}
                  daily-mining-cube:
                    type: daily
                    weight: 10
                    requirements: {systems: [mining-cube]}
                    rewards: {tokens: {type: sky-tokens, amount: 1}}
                    objective: {action: mining-cube-block-mined, target: 1}
                  weekly-dailies:
                    type: weekly
                    weight: 10
                    rewards: {tokens: {type: sky-tokens, amount: 1}}
                    objective: {action: daily-completed, target: 1}
                  weekly-xp:
                    type: weekly
                    weight: 10
                    rewards: {tokens: {type: sky-tokens, amount: 1}}
                    objective: {action: island-xp-gained, target: 10}
                  island-harvest:
                    type: island
                    weight: 10
                    rewards: {tokens: {type: sky-tokens, amount: 1, scope: island-once}}
                    objective: {action: crop-harvested, target: 3}
                """);
        final QuestConfig config = new QuestConfig(null);
        config.parse(yaml);
        return config;
    }

    private Island island(final UUID owner) {
        return new Island(UUID.randomUUID(), owner, "Owner", "world", 0,
                0, 64, 0, 100, "default", 0L, 0.5, 65, 0.5, 0f, 0f);
    }
}
