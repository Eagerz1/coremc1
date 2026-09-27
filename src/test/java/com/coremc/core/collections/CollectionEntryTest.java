package com.coremc.core.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.progress.ProgressAction;
import com.coremc.core.progress.ProgressSource;
import com.coremc.core.progress.reward.Reward;
import com.coremc.core.progress.reward.RewardType;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** Entry matching, normalisation and milestone helpers. */
class CollectionEntryTest {

    private static CollectionEntry entry(final Set<String> keys, final Set<ProgressSource> sources) {
        return new CollectionEntry("Cobblestone", CollectionCategory.MINING, "Cobblestone",
                Material.COBBLESTONE, ProgressAction.MINE_BLOCK, keys, sources,
                List.of(new CollectionMilestone(0, 10, List.of()),
                        new CollectionMilestone(1, 100, List.of())), false, "");
    }

    @Test
    void idsAreLowerCasedAndOptionalFieldsDefault() {
        final CollectionEntry entry = new CollectionEntry(" Cobble ", CollectionCategory.MINING,
                "  ", Material.COBBLESTONE, ProgressAction.MINE_BLOCK, null, null, null, false,
                null);
        assertEquals("cobble", entry.id());
        assertEquals("cobble", entry.display());
        assertTrue(entry.keys().isEmpty());
        assertTrue(entry.sources().isEmpty());
        assertEquals(0, entry.tiers());
        assertEquals(0, entry.finalAmount());
        assertEquals("", entry.hint());
        assertThrows(NullPointerException.class, () -> new CollectionEntry(null,
                CollectionCategory.MINING, "x", Material.STONE, ProgressAction.MINE_BLOCK,
                null, null, null, false, null));
    }

    @Test
    void matchingIsByActionThenKeyThenSource() {
        final CollectionEntry entry = entry(Set.of("cobblestone", "stone"), Set.of());
        assertTrue(entry.accepts(ProgressAction.MINE_BLOCK, "cobblestone", ProgressSource.WORLD));
        assertTrue(entry.accepts(ProgressAction.MINE_BLOCK, "COBBLESTONE", ProgressSource.WORLD));
        assertTrue(entry.accepts(ProgressAction.MINE_BLOCK, "stone", ProgressSource.GENERATOR));
        assertFalse(entry.accepts(ProgressAction.MINE_BLOCK, "diamond", ProgressSource.WORLD));
        assertFalse(entry.accepts(ProgressAction.HARVEST_CROP, "cobblestone",
                ProgressSource.WORLD));
        assertFalse(entry.accepts(ProgressAction.MINE_BLOCK, null, ProgressSource.WORLD));
    }

    @Test
    void anEntryWithoutKeysAcceptsEverySubject() {
        final CollectionEntry entry = entry(Set.of(), Set.of());
        assertTrue(entry.accepts(ProgressAction.MINE_BLOCK, "anything", ProgressSource.WORLD));
        assertTrue(entry.accepts(ProgressAction.MINE_BLOCK, null, ProgressSource.WORLD));
    }

    @Test
    void sourceFiltersKeepGeneratorOutputOutOfHandMinedCollections() {
        final CollectionEntry handMined = entry(Set.of("cobblestone"), Set.of(ProgressSource.WORLD));
        assertTrue(handMined.accepts(ProgressAction.MINE_BLOCK, "cobblestone",
                ProgressSource.WORLD));
        assertFalse(handMined.accepts(ProgressAction.MINE_BLOCK, "cobblestone",
                ProgressSource.GENERATOR));
    }

    @Test
    void milestonesExposeTheirTierNumbersAndRewardShape() {
        final CollectionMilestone coins = new CollectionMilestone(0, 100,
                List.of(Reward.amount(RewardType.COINS, "", 500, "")));
        final CollectionMilestone title = new CollectionMilestone(1, 200,
                List.of(Reward.unlock(RewardType.TITLE, "miner", "Miner")));
        final CollectionMilestone nothing = new CollectionMilestone(2, 300, null);
        assertEquals(1, coins.tier());
        assertEquals(2, title.tier());
        assertTrue(coins.hasManualReward());
        assertFalse(coins.automaticOnly());
        assertFalse(title.hasManualReward());
        assertTrue(title.automaticOnly());
        assertFalse(nothing.hasManualReward());
        assertFalse(nothing.automaticOnly());
        assertTrue(nothing.rewards().isEmpty());
    }

    @Test
    void discoveryEntriesKnowWhatTheyAre() {
        final CollectionEntry discovery = new CollectionEntry("relic",
                CollectionCategory.DISCOVERIES, "Relic", Material.HEART_OF_THE_SEA,
                ProgressAction.DISCOVERY, Set.of("relic"), Set.of(),
                List.of(new CollectionMilestone(0, 1, List.of())), true, "Something in the deep.");
        assertTrue(discovery.discovery());
        assertTrue(discovery.hidden());
        assertEquals("Something in the deep.", discovery.hint());
        assertFalse(entry(Set.of(), Set.of()).discovery());
    }

    @Test
    void discoveryRecordsKeepTheFirstTimestamp() {
        assertFalse(DiscoveryRecord.NONE.discovered());
        final DiscoveryRecord first = DiscoveryRecord.NONE.plus(1, 1_000L);
        assertTrue(first.discovered());
        assertEquals(1, first.count());
        assertEquals(1_000L, first.first());
        final DiscoveryRecord second = first.plus(2, 9_000L);
        assertEquals(3, second.count());
        assertEquals(1_000L, second.first());
        assertEquals(second, second.plus(0, 10_000L));
        assertEquals(second, second.plus(-5, 10_000L));
    }
}
