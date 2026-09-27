package com.coremc.core.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.progress.ProgressAction;
import com.coremc.core.progress.ProgressEvent;
import com.coremc.core.progress.ProgressSource;
import com.coremc.core.progress.adapter.ExternalSystems;
import com.coremc.core.progress.reward.PendingRewardStore;
import com.coremc.core.progress.reward.RewardService;
import com.coremc.core.progress.reward.RewardType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Collection service end to end, without a server: counting,
 * milestones, exactly-once claiming, unlocks and completion.
 */
class CollectionServiceTest {

    @TempDir
    Path folder;

    /** In-memory store so the service can be driven hard. */
    private static final class MemoryStore implements CollectionStore {
        private Map<UUID, CollectionProfile> data = new LinkedHashMap<>();
        private int saves;

        @Override
        public Map<UUID, CollectionProfile> load() {
            return new LinkedHashMap<>(data);
        }

        @Override
        public void save(final Map<UUID, CollectionProfile> profiles) {
            data = new LinkedHashMap<>(profiles);
            saves++;
        }
    }

    private static final String YAML = """
            recipes:
              cobble_compressor:
                display: "Cobble Compressor"
                icon: STONE
                requires: {collection: cobblestone, tier: 2}
                ingredients:
                  - COBBLESTONE:64
                result: STONE
                result-amount: 64
            collections:
              cobblestone:
                category: mining
                display: "Cobblestone"
                icon: COBBLESTONE
                action: mine_block
                keys: [cobblestone]
                milestones:
                  - amount: 10
                    rewards:
                      - {type: coins, amount: 500}
                  - amount: 50
                    rewards:
                      - {type: recipe, id: cobble_compressor}
                  - amount: 100
                    rewards:
                      - {type: title, id: stonebreaker, display: "Stonebreaker"}
                      - {type: sky_tokens, amount: 5}
              iron:
                category: mining
                display: "Iron"
                icon: IRON_INGOT
                action: mine_block
                keys: [iron_ore]
                sources: [world]
                milestones:
                  - amount: 10
              relic:
                category: discoveries
                display: "Relic"
                icon: HEART_OF_THE_SEA
                action: discovery
                keys: [relic]
                hidden: true
                hint: "Something rests in the deep."
                milestones:
                  - amount: 1
            """;

    private CollectionConfig config() {
        final CollectionConfig config = new CollectionConfig(null);
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

    private CollectionService service(final CollectionStore store) {
        final CollectionService service =
                new CollectionService(config(), store, rewards(), null);
        service.load();
        return service;
    }

    // ------------------------------------------------------------------
    // counting
    // ------------------------------------------------------------------

    @Test
    void anEventOnlyFeedsTheCollectionsThatAcceptIt() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        service.accept(ProgressEvent.of(player, ProgressAction.MINE_BLOCK, "cobblestone", 5,
                ProgressSource.WORLD));
        assertEquals(5, service.amount(player, "cobblestone"));
        assertEquals(0, service.amount(player, "iron"));

        // the iron entry only accepts the world source
        service.accept(ProgressEvent.of(player, ProgressAction.MINE_BLOCK, "iron_ore", 3,
                ProgressSource.GENERATOR));
        assertEquals(0, service.amount(player, "iron"));
        service.accept(ProgressEvent.of(player, ProgressAction.MINE_BLOCK, "iron_ore", 3,
                ProgressSource.WORLD));
        assertEquals(3, service.amount(player, "iron"));
    }

    @Test
    void crossingSeveralTiersAtOnceAwardsEveryTierExactlyOnce() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        final List<MilestoneAward> awards = service.accept(ProgressEvent.of(player,
                ProgressAction.MINE_BLOCK, "cobblestone", 100, ProgressSource.WORLD));
        assertEquals(3, awards.size());
        assertEquals(1, awards.get(0).milestone().tier());
        assertEquals(3, awards.get(2).milestone().tier());
        assertTrue(awards.get(2).completesEntry());
        assertFalse(awards.get(0).completesEntry());
        // no further awards for the same totals
        assertTrue(service.accept(ProgressEvent.of(player, ProgressAction.MINE_BLOCK,
                "cobblestone", 1, ProgressSource.WORLD)).isEmpty());
    }

    @Test
    void permanentRewardsApplyThemselvesAndManualOnesWait() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        final List<MilestoneAward> awards = service.accept(ProgressEvent.of(player,
                ProgressAction.MINE_BLOCK, "cobblestone", 100, ProgressSource.WORLD));
        final MilestoneAward third = awards.get(2);
        assertEquals(1, third.automatic().size());
        assertEquals(RewardType.TITLE, third.automatic().get(0).type());
        assertTrue(third.hasClaimable());
        assertEquals(RewardType.SKY_TOKENS, third.claimable().get(0).type());
        assertTrue(service.hasUnlock(player, RewardType.TITLE, "stonebreaker"));
        assertEquals(java.util.Set.of("stonebreaker"),
                service.unlocksOfType(player, RewardType.TITLE));
        assertTrue(service.recipeUnlocked(player, "cobble_compressor"));
    }

    @Test
    void theNotifierSeesEveryMilestoneOnce() {
        final CollectionService service = service(new MemoryStore());
        final List<String> seen = new ArrayList<>();
        service.notifier((player, award) -> seen.add(award.entry().id()
                + ":" + award.milestone().tier()));
        final UUID player = UUID.randomUUID();
        service.accept(ProgressEvent.of(player, ProgressAction.MINE_BLOCK, "cobblestone", 60,
                ProgressSource.WORLD));
        assertEquals(List.of("cobblestone:1", "cobblestone:2"), seen);
    }

    @Test
    void nonsenseEventsAreIgnored() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        assertTrue(service.accept(null).isEmpty());
        assertTrue(service.accept(ProgressEvent.of(player, ProgressAction.MINE_BLOCK,
                "cobblestone", 0, ProgressSource.WORLD)).isEmpty());
        assertTrue(service.accept(ProgressEvent.of(player, ProgressAction.SEASON_LEVEL, "x", 1,
                ProgressSource.WORLD)).isEmpty());
        assertTrue(service.record(null, null, 5, 0L).isEmpty());
        assertTrue(service.add(player, "ghost", 10).isEmpty());
        assertFalse(service.set(player, "ghost", 10));
    }

    @Test
    void discoveriesRecordWhenTheyWereFirstFound() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        final CollectionEntry relic = service.config().byId("relic");
        assertFalse(service.discovered(player, relic));
        service.record(player, relic, 1, 4_242L);
        assertTrue(service.discovered(player, relic));
        assertEquals(4_242L, service.profile(player).discovery("relic").first());
        assertTrue(service.discovered(player, service.config().byId("cobblestone")));
        assertFalse(service.discovered(player, null));
    }

    // ------------------------------------------------------------------
    // claiming
    // ------------------------------------------------------------------

    @Test
    void claimingIsExactlyOnceAndParksWhatItCannotDeliver() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        service.add(player, "cobblestone", 100);
        assertEquals(ClaimResult.PENDING, service.claim(player, "cobblestone", 1));
        assertEquals(ClaimResult.ALREADY_CLAIMED, service.claim(player, "cobblestone", 1));
        assertEquals(ClaimResult.NOTHING_TO_CLAIM, service.claim(player, "cobblestone", 2));
        assertEquals(ClaimResult.PENDING, service.claim(player, "cobblestone", 3));
        assertEquals(ClaimResult.UNKNOWN, service.claim(player, "cobblestone", 9));
        assertEquals(ClaimResult.UNKNOWN, service.claim(player, "ghost", 1));
        assertEquals(ClaimResult.UNKNOWN, service.claim(null, "cobblestone", 1));
    }

    @Test
    void anUnreachedTierCannotBeClaimed() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        service.add(player, "cobblestone", 5);
        assertEquals(ClaimResult.NOT_REACHED, service.claim(player, "cobblestone", 1));
        assertEquals(0, service.claimableCount(player));
    }

    @Test
    void claimableCountsOnlyCountHandClaimedRewards() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        service.add(player, "cobblestone", 100);
        // tier 1 coins + tier 3 sky tokens; tier 2 is an automatic recipe
        assertEquals(2, service.claimableCount(player));
        final CollectionEntry cobble = service.config().byId("cobblestone");
        assertTrue(service.claimable(player, cobble, 1));
        assertFalse(service.claimable(player, cobble, 2));
        assertFalse(service.claimable(player, cobble, 99));
        assertFalse(service.claimable(player, null, 1));
        service.claim(player, "cobblestone", 1);
        assertEquals(1, service.claimableCount(player));
    }

    // ------------------------------------------------------------------
    // queries + persistence
    // ------------------------------------------------------------------

    @Test
    void completionPercentagesCoverEntriesCategoriesAndTheWholeSet() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        assertEquals(0, service.totalPercent(player));
        service.add(player, "cobblestone", 100);
        service.add(player, "iron", 10);
        assertEquals(100, service.categoryPercent(player, CollectionCategory.MINING));
        assertEquals(2, service.completedIn(player, CollectionCategory.MINING));
        assertEquals(0, service.categoryPercent(player, CollectionCategory.DISCOVERIES));
        assertEquals(66, service.totalPercent(player));
        assertEquals(2, service.completedTotal(player));
        assertEquals(3, service.tier(player, service.config().byId("cobblestone")));
        assertEquals(0, service.tier(player, null));
    }

    @Test
    void adminSetOverwritesWithoutGrantingRewards() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        assertTrue(service.set(player, "cobblestone", 100));
        assertEquals(100, service.amount(player, "cobblestone"));
        assertFalse(service.hasUnlock(player, RewardType.TITLE, "stonebreaker"));
        assertTrue(service.set(player, "cobblestone", -5));
        assertEquals(0, service.amount(player, "cobblestone"));
    }

    @Test
    void progressIsWrittenThroughAndReadBackOnReload() throws IOException {
        final MemoryStore store = new MemoryStore();
        final CollectionService first = service(store);
        final UUID player = UUID.randomUUID();
        first.add(player, "cobblestone", 100);
        first.claim(player, "cobblestone", 1);
        first.save();

        final CollectionService second = service(store);
        assertEquals(100, second.amount(player, "cobblestone"));
        assertTrue(second.profile(player).claimed("cobblestone", 1));
        assertTrue(second.hasUnlock(player, RewardType.TITLE, "stonebreaker"));
        assertEquals(1, second.profiles().size());
        assertFalse(store.load().isEmpty());
    }

    @Test
    void aDisabledConfigNeverThrows() {
        final CollectionService service =
                new CollectionService(CollectionConfig.disabled(), null, null, null);
        service.load();
        final UUID player = UUID.randomUUID();
        assertTrue(service.accept(ProgressEvent.of(player, ProgressAction.MINE_BLOCK,
                "cobblestone", 5, ProgressSource.WORLD)).isEmpty());
        assertEquals(0, service.totalPercent(player));
        assertEquals(0, service.claimableCount(player));
        assertEquals(ClaimResult.UNKNOWN, service.claim(player, "cobblestone", 1));
        service.save();
    }

    @Test
    void unlockKeysAreStableAcrossRestarts() {
        assertEquals("title:stonebreaker",
                CollectionService.unlockKey(RewardType.TITLE, " Stonebreaker "));
        assertEquals("recipe:", CollectionService.unlockKey(RewardType.RECIPE, null));
    }

    @Test
    void recipesStayUnlockedEvenIfTheUnlockRecordIsLost() {
        final CollectionService service = service(new MemoryStore());
        final UUID player = UUID.randomUUID();
        service.set(player, "cobblestone", 60);
        // no stored unlock — derived from the tier instead
        assertFalse(service.hasUnlock(player, RewardType.RECIPE, "cobble_compressor"));
        assertTrue(service.recipeUnlocked(player, "cobble_compressor"));
        assertFalse(service.recipeUnlocked(player, "ghost_recipe"));
    }
}
