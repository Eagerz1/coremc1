package com.coremc.core.achievements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.collections.ClaimResult;
import com.coremc.core.progress.ProgressAction;
import com.coremc.core.progress.ProgressEvent;
import com.coremc.core.progress.ProgressSource;
import com.coremc.core.progress.adapter.ExternalSystems;
import com.coremc.core.progress.reward.PendingRewardStore;
import com.coremc.core.progress.reward.RewardService;
import com.coremc.core.progress.reward.RewardType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Achievement service: counting modes, earning, points, claims. */
class AchievementServiceTest {

    @TempDir
    Path folder;

    private static final class MemoryStore implements AchievementStore {
        private Map<UUID, AchievementProfile> data = new LinkedHashMap<>();

        @Override
        public Map<UUID, AchievementProfile> load() {
            return new LinkedHashMap<>(data);
        }

        @Override
        public void save(final Map<UUID, AchievementProfile> profiles) {
            data = new LinkedHashMap<>(profiles);
        }
    }

    private static final String YAML = """
            settings:
              announce: true
            achievements:
              first_strike:
                category: gathering
                display: "First Strike"
                description: "Break one block."
                icon: WOODEN_PICKAXE
                difficulty: common
                action: mine_block
                mode: total
                requirement: 1
                rewards:
                  - {type: coins, amount: 1000}
              quarry_hand:
                category: gathering
                display: "Quarry Hand"
                description: "Mine 100 blocks."
                icon: STONE_PICKAXE
                difficulty: rare
                action: mine_block
                mode: total
                requirement: 100
                rewards:
                  - {type: title, id: quarryhand, display: "Quarry Hand"}
              skyline:
                category: island
                display: "Skyline"
                description: "Reach island level 10."
                icon: GRASS_BLOCK
                difficulty: epic
                action: island_level
                mode: max
                requirement: 10
              bestiary:
                category: combat
                display: "Bestiary"
                description: "Kill three different mobs."
                icon: BOOK
                difficulty: rare
                action: slayer_kill
                mode: unique
                requirement: 3
              the_deep_end:
                category: secret
                display: "The Deep End"
                description: "You found the edge of the world."
                icon: ENDER_EYE
                difficulty: legendary
                action: discovery
                keys: [void_edge]
                secret: true
                requirement: 1
              season_champion:
                category: seasonal
                display: "Season Champion"
                description: "Finish a season at the top."
                icon: CLOCK
                difficulty: prestige
                action: season_level
                mode: max
                requirement: 50
            """;

    private AchievementConfig config() {
        final AchievementConfig config = new AchievementConfig(null);
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(YAML);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(yaml);
        return config;
    }

    private RewardService rewards() {
        final PendingRewardStore pending =
                new PendingRewardStore(folder.resolve("pending.yml"), null);
        pending.load();
        return new RewardService(null, new ExternalSystems(), pending, null);
    }

    private AchievementService service(final AchievementStore store) {
        final AchievementService service =
                new AchievementService(config(), store, rewards(), null);
        service.load();
        return service;
    }

    private static ProgressEvent mine(final UUID player, final long amount) {
        return ProgressEvent.of(player, ProgressAction.MINE_BLOCK, "cobblestone", amount,
                ProgressSource.WORLD);
    }

    // ------------------------------------------------------------------
    // counting modes
    // ------------------------------------------------------------------

    @Test
    void totalModeSumsUntilTheRequirementIsMet() {
        final AchievementService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        final List<Achievement> first = service.accept(mine(player, 1));
        assertEquals(1, first.size());
        assertEquals("first_strike", first.get(0).id());
        assertTrue(service.earned(player, "first_strike"));
        assertFalse(service.earned(player, "quarry_hand"));
        assertTrue(service.accept(mine(player, 98)).isEmpty());
        assertEquals(1, service.accept(mine(player, 1)).size());
        assertTrue(service.earned(player, "quarry_hand"));
    }

    @Test
    void maxModeKeepsTheHighestValueAndNeverRegresses() {
        final AchievementService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        final Achievement skyline = service.config().byId("skyline");
        service.accept(ProgressEvent.of(player, ProgressAction.ISLAND_LEVEL, "level", 4,
                ProgressSource.WORLD));
        assertEquals(4, service.progress(player, skyline));
        service.accept(ProgressEvent.of(player, ProgressAction.ISLAND_LEVEL, "level", 2,
                ProgressSource.WORLD));
        assertEquals(4, service.progress(player, skyline));
        service.accept(ProgressEvent.of(player, ProgressAction.ISLAND_LEVEL, "level", 12,
                ProgressSource.WORLD));
        assertTrue(service.earned(player, "skyline"));
        assertEquals(10, service.progress(player, skyline), "progress is capped at the goal");
    }

    @Test
    void uniqueModeCountsDistinctSubjects() {
        final AchievementService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        service.accept(ProgressEvent.of(player, ProgressAction.SLAYER_KILL, "zombie", 10,
                ProgressSource.WORLD));
        service.accept(ProgressEvent.of(player, ProgressAction.SLAYER_KILL, "zombie", 10,
                ProgressSource.WORLD));
        assertFalse(service.earned(player, "bestiary"));
        service.accept(ProgressEvent.of(player, ProgressAction.SLAYER_KILL, "skeleton", 1,
                ProgressSource.WORLD));
        service.accept(ProgressEvent.of(player, ProgressAction.SLAYER_KILL, "creeper", 1,
                ProgressSource.WORLD));
        assertTrue(service.earned(player, "bestiary"));
    }

    @Test
    void anAchievementIsOnlyEarnedOnceAndIsNeverRevoked() {
        final AchievementService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        assertEquals(1, service.accept(mine(player, 1)).size());
        assertTrue(service.accept(mine(player, 1)).isEmpty());
        final long when = service.profile(player).earnedAt("first_strike");
        service.accept(mine(player, 500));
        assertEquals(when, service.profile(player).earnedAt("first_strike"));
        assertFalse(service.grant(player, "first_strike"), "already earned");
    }

    @Test
    void nonsenseEventsAreIgnored() {
        final AchievementService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        assertTrue(service.accept(null).isEmpty());
        assertTrue(service.accept(mine(player, 0)).isEmpty());
        assertTrue(service.accept(ProgressEvent.of(player, ProgressAction.QUEST_COMPLETE, "x", 1,
                ProgressSource.QUEST)).isEmpty());
        assertFalse(service.grant(player, "ghost"));
        assertFalse(service.grant(null, "first_strike"));
        assertFalse(service.earned(player, "ghost"));
    }

    // ------------------------------------------------------------------
    // seasons, secrets, points
    // ------------------------------------------------------------------

    @Test
    void seasonalAchievementsAreStampedWithTheSeasonTheyWereEarnedIn() {
        final AchievementService service = service(new MemoryStore());
        service.seasonSupplier(() -> 3);
        final UUID player = UUID.randomUUID();
        service.accept(ProgressEvent.of(player, ProgressAction.SEASON_LEVEL, "level", 50,
                ProgressSource.SEASON));
        final Achievement champion = service.config().byId("season_champion");
        assertTrue(service.earned(player, "season_champion"));
        assertEquals(3, service.earnedSeason(player, champion));
        assertEquals(-1, service.earnedSeason(player, service.config().byId("first_strike")));
        assertEquals(-1, service.earnedSeason(player, null));
    }

    @Test
    void withoutASeasonSupplierASeasonalEarnStillRecordsSomethingSane() {
        final AchievementService service = service(new MemoryStore());
        service.seasonSupplier(null);
        final UUID player = UUID.randomUUID();
        service.accept(ProgressEvent.of(player, ProgressAction.SEASON_LEVEL, "level", 50,
                ProgressSource.SEASON));
        assertEquals(0, service.earnedSeason(player, service.config().byId("season_champion")));
    }

    @Test
    void secretsStayHiddenUntilTheyAreEarned() {
        final AchievementService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        final Achievement secret = service.config().byId("the_deep_end");
        assertTrue(service.hidden(player, secret));
        assertFalse(service.hidden(player, service.config().byId("first_strike")));
        service.accept(ProgressEvent.of(player, ProgressAction.DISCOVERY, "void_edge", 1,
                ProgressSource.DISCOVERY));
        assertFalse(service.hidden(player, secret));
        assertFalse(service.hidden(player, null));
    }

    @Test
    void pointsAreSummedFromEarnedAchievementsOnly() {
        final AchievementService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        assertEquals(0, service.points(player));
        assertEquals(0, service.earnedCount(player));
        service.accept(mine(player, 100));
        assertEquals(5 + 15, service.points(player));
        assertEquals(2, service.earnedCount(player));
        assertEquals(2, service.earnedIn(player, AchievementCategory.GATHERING));
        assertEquals(0, service.earnedIn(player, AchievementCategory.COMBAT));
        assertEquals(215, service.config().maxPoints());
        assertEquals(9, service.percent(player), "20 of 215 points, floored");
    }

    @Test
    void anAdminGrantAwardsTheAchievementAndItsPermanentRewards() {
        final AchievementService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        assertTrue(service.grant(player, "quarry_hand"));
        assertTrue(service.earned(player, "quarry_hand"));
        assertTrue(service.hasUnlock(player, RewardType.TITLE, "quarryhand"));
        assertEquals(15, service.points(player));
        assertEquals(100, service.progress(player, service.config().byId("quarry_hand")));
    }

    @Test
    void theNotifierIsToldOnceWithWhetherAnythingIsClaimable() {
        final AchievementService service = service(new MemoryStore());
        final List<String> seen = new ArrayList<>();
        service.notifier((player, achievement, claimable) ->
                seen.add(achievement.id() + ":" + claimable));
        final UUID player = UUID.randomUUID();
        service.accept(mine(player, 100));
        assertEquals(List.of("first_strike:true", "quarry_hand:false"), seen);
    }

    // ------------------------------------------------------------------
    // claiming + persistence
    // ------------------------------------------------------------------

    @Test
    void claimingIsExactlyOnceAndParksWhatItCannotDeliver() {
        final AchievementService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        assertEquals(ClaimResult.NOT_REACHED, service.claim(player, "first_strike"));
        service.accept(mine(player, 100));
        assertEquals(1, service.claimableCount(player));
        assertEquals(ClaimResult.PENDING, service.claim(player, "first_strike"));
        assertEquals(ClaimResult.ALREADY_CLAIMED, service.claim(player, "first_strike"));
        assertEquals(ClaimResult.NOTHING_TO_CLAIM, service.claim(player, "quarry_hand"));
        assertEquals(ClaimResult.UNKNOWN, service.claim(player, "ghost"));
        assertEquals(ClaimResult.UNKNOWN, service.claim(null, "first_strike"));
        assertEquals(0, service.claimableCount(player));
        assertFalse(service.claimable(player, null));
    }

    @Test
    void everythingSurvivesAReload() {
        final MemoryStore store = new MemoryStore();
        final AchievementService first = service(store);
        first.seasonSupplier(() -> 2);
        final UUID player = UUID.randomUUID();
        first.accept(mine(player, 100));
        first.accept(ProgressEvent.of(player, ProgressAction.SEASON_LEVEL, "level", 50,
                ProgressSource.SEASON));
        first.claim(player, "first_strike");
        first.save();

        final AchievementService second = service(store);
        assertTrue(second.earned(player, "first_strike"));
        assertTrue(second.profile(player).claimed("first_strike"));
        assertEquals(2, second.earnedSeason(player, second.config().byId("season_champion")));
        assertEquals(5 + 15 + 100, second.points(player));
        assertEquals(1, second.profiles().size());
    }

    @Test
    void aDisabledConfigNeverThrows() {
        final AchievementService service =
                new AchievementService(AchievementConfig.disabled(), null, null, null);
        service.load();
        final UUID player = UUID.randomUUID();
        assertTrue(service.accept(mine(player, 10)).isEmpty());
        assertEquals(0, service.points(player));
        assertEquals(0, service.percent(player));
        assertEquals(0, service.claimableCount(player));
        assertEquals(ClaimResult.UNKNOWN, service.claim(player, "first_strike"));
        service.save();
    }

    @Test
    void unlockKeysAreStableAcrossRestarts() {
        assertEquals("title:quarryhand",
                AchievementService.unlockKey(RewardType.TITLE, " QuarryHand "));
        assertEquals("cosmetic:", AchievementService.unlockKey(RewardType.COSMETIC, null));
    }
}
