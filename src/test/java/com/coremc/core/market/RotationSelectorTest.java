package com.coremc.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rotation selection: pool counts, chance slots at their boundaries,
 * repeat avoidance where the pool allows it, and price bands that
 * always resolve inside their range.
 */
class RotationSelectorTest {

    private static List<MarketOffer> pools() {
        final List<MarketOffer> offers = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            offers.add(MarketTestSupport.offer("common-" + index, MarketRarity.COMMON,
                    50_000, 5, 2));
        }
        for (int index = 0; index < 4; index++) {
            offers.add(MarketTestSupport.offer("rare-" + index, MarketRarity.RARE,
                    300_000, 4, 1));
        }
        offers.add(MarketTestSupport.offer("epic-0", MarketRarity.EPIC, 800_000, 3, 1));
        offers.add(MarketTestSupport.offer("epic-1", MarketRarity.EPIC, 800_000, 3, 1));
        offers.add(MarketTestSupport.offer("legendary-0", MarketRarity.LEGENDARY,
                1_500_000, 2, 1));
        offers.add(MarketTestSupport.offer("cosmetic-0", MarketRarity.COSMETIC, 400_000, 5, 1));
        return offers;
    }

    private static Set<String> ids(final List<RotationSelector.Selected> picks) {
        final Set<String> ids = new HashSet<>();
        for (final RotationSelector.Selected pick : picks) {
            ids.add(pick.offer().id());
        }
        return ids;
    }

    @Test
    void shapeValidatesItsNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> new RotationSelector.Shape(-1, 2, 2, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RotationSelector.Shape(3, 2, 2, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RotationSelector.Shape(2, 3, 2, 1, 1.5, 0, 0));
    }

    @Test
    void certainChancesAlwaysAndNeverFire() {
        final RotationSelector.Shape always = new RotationSelector.Shape(2, 2, 2, 1, 1.0, 1.0, 0);
        final RotationSelector.Shape never = new RotationSelector.Shape(2, 2, 2, 1, 0.0, 0.0, 0);
        for (int seed = 0; seed < 20; seed++) {
            final Set<String> withChance = ids(RotationSelector.select(pools(), always,
                    Set.of(), new Random(seed)));
            assertTrue(withChance.contains("legendary-0"), "seed " + seed);
            assertTrue(withChance.contains("cosmetic-0"), "seed " + seed);
            assertEquals(7, withChance.size(), "2C+2R+1E+1L+1Cos, seed " + seed);
            final Set<String> withoutChance = ids(RotationSelector.select(pools(), never,
                    Set.of(), new Random(seed)));
            assertTrue(!withoutChance.contains("legendary-0"));
            assertTrue(!withoutChance.contains("cosmetic-0"));
            assertEquals(5, withoutChance.size());
        }
    }

    @Test
    void commonCountStaysInsideItsRange() {
        final RotationSelector.Shape shape = new RotationSelector.Shape(2, 3, 2, 1, 0, 0, 0);
        for (int seed = 0; seed < 40; seed++) {
            long commons = RotationSelector.select(pools(), shape, Set.of(), new Random(seed))
                    .stream().filter(pick -> pick.offer().pool() == MarketRarity.COMMON).count();
            assertTrue(commons >= 2 && commons <= 3, "seed " + seed + ": " + commons);
        }
    }

    @Test
    void previousRotationIsAvoidedWhenThePoolAllows() {
        final RotationSelector.Shape shape = new RotationSelector.Shape(2, 2, 2, 1, 0, 0, 0);
        final Set<String> previous = Set.of("common-0", "common-1", "rare-0", "rare-1");
        for (int seed = 0; seed < 40; seed++) {
            final Set<String> next = ids(RotationSelector.select(pools(), shape, previous,
                    new Random(seed)));
            // 5 commons / 4 rares exist, 2 of each needed: fresh ones suffice
            for (final String repeat : previous) {
                assertTrue(!next.contains(repeat), "seed " + seed + " repeated " + repeat);
            }
        }
    }

    @Test
    void tinyPoolsFallBackToRepeatsInsteadOfFailing() {
        final List<MarketOffer> tiny = List.of(
                MarketTestSupport.offer("only-common", MarketRarity.COMMON, 50_000, 5, 2));
        final RotationSelector.Shape shape = new RotationSelector.Shape(1, 1, 0, 0, 0, 0, 0);
        final List<RotationSelector.Selected> picks = RotationSelector.select(tiny, shape,
                Set.of("only-common"), new Random(1));
        assertEquals(1, picks.size(), "a one-offer pool must still rotate");
        assertEquals("only-common", picks.get(0).offer().id());
    }

    @Test
    void pricesResolveInsideTheirBand() {
        final CostBand band = new CostBand(700_000, 900_000, 1_000, 2_000, 0, 0);
        for (int seed = 0; seed < 200; seed++) {
            final MarketCost cost = band.resolve(new Random(seed));
            assertTrue(cost.money() >= 700_000 && cost.money() <= 900_000,
                    "money " + cost.money());
            assertTrue(cost.tokens() >= 1_000 && cost.tokens() <= 2_000,
                    "tokens " + cost.tokens());
            assertEquals(0, cost.credits());
        }
        // fixed bands are exact
        assertEquals(150, CostBand.fixed(0, 0, 150).resolve(new Random(7)).credits());
    }

    @Test
    void priceRoundingSnapsToCleanStepsInsideTheBand() {
        assertEquals(750_000, CostBand.round(748_291, 700_000, 900_000));
        assertEquals(700_000, CostBand.round(700_001, 700_000, 900_000));
        assertEquals(900_000, CostBand.round(899_999, 700_000, 900_000));
        // tiny bands keep exact values
        assertEquals(57, CostBand.round(57, 50, 90));
    }

    @Test
    void bandsValidateTheirShape() {
        assertThrows(IllegalArgumentException.class, () -> new CostBand(-1, 5, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CostBand(10, 5, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CostBand(0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> MarketCost.parse("1:2"));
        assertThrows(IllegalArgumentException.class, () -> new MarketCost(0, 0, 0));
        final MarketCost cost = new MarketCost(750_000, 0, 0);
        assertEquals(cost, MarketCost.parse(cost.serialize()));
        assertTrue(cost.moneyOnly());
        assertEquals("$750,000", cost.text());
        assertEquals("$250,000 + 2,000 \u1D1B\u1D0F\u1D0B\u1D07\u0274s",
                new MarketCost(250_000, 2_000, 0).text());
    }
}
