package com.coremc.core.gens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The placed-generator record: keys, stacks and tier changes. */
class GeneratorEntryTest {

    private static final UUID ISLAND = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void keyIdentifiesTheBlock() {
        final GeneratorEntry entry = new GeneratorEntry("w", -3, 64, 12, "iron", ISLAND, OWNER);
        assertEquals("w:-3:64:12", entry.key());
        assertNotEquals(entry.key(),
                new GeneratorEntry("w", -3, 65, 12, "iron", ISLAND, OWNER).key());
    }

    @Test
    void singleGeneratorsHaveStackOne() {
        assertEquals(1, new GeneratorEntry("w", 0, 0, 0, "iron", ISLAND, OWNER).amount());
    }

    @Test
    void stackCountsAreNeverBelowOne() {
        assertEquals(1, new GeneratorEntry("w", 0, 0, 0, "iron", ISLAND, OWNER, 0).amount());
        assertEquals(1, new GeneratorEntry("w", 0, 0, 0, "iron", ISLAND, OWNER, -7).amount());
    }

    @Test
    void withAmountKeepsEverythingElse() {
        final GeneratorEntry entry = new GeneratorEntry("w", 1, 2, 3, "iron", ISLAND, OWNER, 4);
        final GeneratorEntry bigger = entry.withAmount(9);
        assertEquals(9, bigger.amount());
        assertEquals(entry.key(), bigger.key());
        assertEquals("iron", bigger.genId());
        assertEquals(OWNER, bigger.owner());
    }

    @Test
    void withGeneratorUpgradesInPlace() {
        final GeneratorEntry entry = new GeneratorEntry("w", 1, 2, 3, "iron", ISLAND, OWNER, 4);
        final GeneratorEntry upgraded = entry.withGenerator("gold");
        assertEquals("gold", upgraded.genId());
        assertEquals(4, upgraded.amount(), "an upgrade never loses the stack");
        assertEquals(entry.key(), upgraded.key());
    }

    @Test
    void chunkCoordinatesFollowTheBlock() {
        final GeneratorEntry entry = new GeneratorEntry("w", 33, 64, -17, "iron", ISLAND, OWNER);
        assertEquals(2, entry.chunkX());
        assertEquals(-2, entry.chunkZ());
    }
}
