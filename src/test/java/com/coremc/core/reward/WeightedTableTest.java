package com.coremc.core.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Weighted selection boundaries: no roll can fall through the table,
 * shown chances match rolled chances, bad weights fail loudly.
 */
class WeightedTableTest {

    private static RewardDef def(final String id, final double weight) {
        return new RewardDef(id, RewardType.MONEY, "&aMoney", "common", 100, 200, weight,
                null, null);
    }

    @Test
    void rejectsEmptyAndNonPositiveWeights() {
        assertThrows(IllegalArgumentException.class, () -> new WeightedTable(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedTable(List.of(def("a", 0))));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedTable(List.of(def("a", -3))));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedTable(List.of(def("a", Double.POSITIVE_INFINITY))));
    }

    @Test
    void boundaryRollsNeverFallThrough() {
        final WeightedTable table = new WeightedTable(
                List.of(def("first", 30), def("middle", 50), def("last", 20)));
        assertEquals("first", table.pick(0.0).id());
        assertEquals("first", table.pick(0.2999).id());
        assertEquals("middle", table.pick(0.3).id());
        assertEquals("middle", table.pick(0.7999).id());
        assertEquals("last", table.pick(0.8).id());
        assertEquals("last", table.pick(0.999999).id());
        // clamped: rolls at/over 1 and under 0 stay inside the table
        assertEquals("last", table.pick(1.0).id());
        assertEquals("last", table.pick(5.0).id());
        assertEquals("first", table.pick(-1.0).id());
    }

    @Test
    void singleEntryAlwaysWins() {
        final WeightedTable table = new WeightedTable(List.of(def("only", 0.5)));
        assertEquals("only", table.pick(0.0).id());
        assertEquals("only", table.pick(0.5).id());
        assertEquals("only", table.pick(1.0).id());
    }

    @Test
    void chancesAreExactAndSumTo100() {
        final WeightedTable table = new WeightedTable(
                List.of(def("a", 30), def("b", 50), def("c", 20)));
        assertEquals(30.0, table.chancePercent(table.entries().get(0)), 1e-9);
        assertEquals(50.0, table.chancePercent(table.entries().get(1)), 1e-9);
        assertEquals(20.0, table.chancePercent(table.entries().get(2)), 1e-9);
        double sum = 0;
        for (final RewardDef entry : table.entries()) {
            sum += table.chancePercent(entry);
        }
        assertEquals(100.0, sum, 1e-9);
        assertEquals("30%", table.chanceText(table.entries().get(0)));
    }

    @Test
    void chanceTextTrimsTrailingZeros() {
        final WeightedTable table = new WeightedTable(List.of(def("a", 1), def("b", 7)));
        assertEquals("12.5%", table.chanceText(table.entries().get(0)));
        assertEquals("87.5%", table.chanceText(table.entries().get(1)));
    }

    @Test
    void amountRollsStayInsideTheRange() {
        final RewardDef def = def("a", 1);
        assertEquals(100, def.rollAmount(0.0));
        assertEquals(200, def.rollAmount(0.999999));
        assertEquals(200, def.rollAmount(1.0));
        assertEquals(100, def.rollAmount(-1.0));
        for (double roll = 0; roll < 1.0; roll += 0.01) {
            final long amount = def.rollAmount(roll);
            assertTrue(amount >= 100 && amount <= 200, "out of range: " + amount);
        }
        // fixed amounts ignore the roll
        final RewardDef fixed = new RewardDef("f", RewardType.KEY, "&bKey", "rare",
                1, 1, 5, null, null);
        assertEquals(1, fixed.rollAmount(0.99));
    }

    @Test
    void rareRaritiesAreExactlyRareEpicLegendary() {
        assertTrue(new RewardDef("a", RewardType.ITEM, "x", "rare", 1, 1, 1, null, null).rare());
        assertTrue(new RewardDef("a", RewardType.ITEM, "x", "EPIC", 1, 1, 1, null, null).rare());
        assertTrue(new RewardDef("a", RewardType.ITEM, "x", "legendary", 1, 1, 1, null, null).rare());
        assertTrue(!new RewardDef("a", RewardType.ITEM, "x", "common", 1, 1, 1, null, null).rare());
        assertTrue(!new RewardDef("a", RewardType.ITEM, "x", "uncommon", 1, 1, 1, null, null).rare());
    }
}
