package com.coremc.core.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.progress.ProgressAction;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** The Collection maths: tiers, next tier, bars and completion. */
class CollectionProgressTest {

    private static final List<CollectionMilestone> CURVE = List.of(
            new CollectionMilestone(0, 250, List.of()),
            new CollectionMilestone(1, 1_000, List.of()),
            new CollectionMilestone(2, 5_000, List.of()),
            new CollectionMilestone(3, 25_000, List.of()));

    static CollectionEntry entry(final String id, final long... amounts) {
        final java.util.List<CollectionMilestone> milestones = new java.util.ArrayList<>();
        for (int index = 0; index < amounts.length; index++) {
            milestones.add(new CollectionMilestone(index, amounts[index], List.of()));
        }
        return new CollectionEntry(id, CollectionCategory.MINING, id, Material.COBBLESTONE,
                ProgressAction.MINE_BLOCK, Set.of(id), Set.of(), milestones, false, "");
    }

    @Test
    void tiersAreCountedFromTheAmountAlone() {
        assertEquals(0, CollectionProgress.tiersReached(0, CURVE));
        assertEquals(0, CollectionProgress.tiersReached(249, CURVE));
        assertEquals(1, CollectionProgress.tiersReached(250, CURVE));
        assertEquals(2, CollectionProgress.tiersReached(4_999, CURVE));
        assertEquals(4, CollectionProgress.tiersReached(25_000, CURVE));
        assertEquals(4, CollectionProgress.tiersReached(9_999_999, CURVE));
        assertEquals(0, CollectionProgress.tiersReached(100, List.of()));
        assertEquals(0, CollectionProgress.tiersReached(100, null));
    }

    @Test
    void theNextTierIsTheFirstOneNotYetReached() {
        assertEquals(250, CollectionProgress.next(0, CURVE).amount());
        assertEquals(1_000, CollectionProgress.next(250, CURVE).amount());
        assertNull(CollectionProgress.next(25_000, CURVE));
        assertNull(CollectionProgress.next(0, null));
        assertNotNull(CollectionProgress.next(0, CURVE));
    }

    @Test
    void completionIsOnlyTrueWhenEveryTierIsDone() {
        assertFalse(CollectionProgress.complete(24_999, CURVE));
        assertTrue(CollectionProgress.complete(25_000, CURVE));
        assertFalse(CollectionProgress.complete(1, List.of()));
        assertFalse(CollectionProgress.complete(1, null));
    }

    @Test
    void theTierBarFillsAcrossTheTierBeingWorkedOn() {
        assertEquals(0.0, CollectionProgress.tierFraction(0, CURVE));
        assertEquals(0.5, CollectionProgress.tierFraction(125, CURVE), 1e-9);
        // halfway between 250 and 1000
        assertEquals(0.5, CollectionProgress.tierFraction(625, CURVE), 1e-9);
        assertEquals(1.0, CollectionProgress.tierFraction(25_000, CURVE));
        assertEquals(1.0, CollectionProgress.tierFraction(0, List.of()));
    }

    @Test
    void everyEntryIsWorthTheSameNoMatterHowManyTiersItHas() {
        final CollectionEntry small = entry("small", 1, 5);
        final CollectionEntry big = entry("big", 10, 20, 30, 40, 50, 60, 70, 80, 90, 100);
        assertEquals(0.5, CollectionProgress.entryCompletion(1, small), 1e-9);
        assertEquals(0.5, CollectionProgress.entryCompletion(50, big), 1e-9);
        assertEquals(1.0, CollectionProgress.entryCompletion(100, big), 1e-9);
        assertEquals(0.0, CollectionProgress.entryCompletion(5, null));
        assertEquals(0.0, CollectionProgress.entryCompletion(5, entry("none")));
    }

    @Test
    void categoryCompletionIsTheMeanOfItsEntries() {
        final CollectionEntry one = entry("one", 10, 20);
        final CollectionEntry two = entry("two", 10, 20);
        final List<CollectionEntry> both = List.of(one, two);
        assertEquals(0.0, CollectionProgress.completion(both, e -> 0));
        assertEquals(0.25, CollectionProgress.completion(both, e -> e.id().equals("one") ? 10 : 0),
                1e-9);
        assertEquals(1.0, CollectionProgress.completion(both, e -> 20));
        assertEquals(0.0, CollectionProgress.completion(List.of(), e -> 10));
        assertEquals(0.0, CollectionProgress.completion(null, e -> 10));
    }

    @Test
    void percentagesAreFlooredSoNinetyNinePointNineIsNeverComplete() {
        assertEquals(0, CollectionProgress.percent(0.0));
        assertEquals(99, CollectionProgress.percent(0.999));
        assertEquals(100, CollectionProgress.percent(1.0));
        assertEquals(100, CollectionProgress.percent(5.0));
        assertEquals(0, CollectionProgress.percent(-1.0));
        assertEquals(0, CollectionProgress.percent(Double.NaN));
    }

    @Test
    void countsOfCompleteAndStartedEntriesAreReported() {
        final List<CollectionEntry> entries = List.of(entry("a", 10), entry("b", 10),
                entry("c", 10));
        assertEquals(0, CollectionProgress.completedCount(entries, e -> 0));
        assertEquals(3, CollectionProgress.completedCount(entries, e -> 10));
        assertEquals(1, CollectionProgress.completedCount(entries,
                e -> e.id().equals("a") ? 10 : 1));
        assertEquals(3, CollectionProgress.discoveredCount(entries, e -> 1));
        assertEquals(0, CollectionProgress.discoveredCount(entries, e -> 0));
        assertEquals(0, CollectionProgress.completedCount(null, e -> 1));
        assertEquals(0, CollectionProgress.discoveredCount(null, e -> 1));
    }
}
