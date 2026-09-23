package com.coremc.core.spawner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stacked spawner / stacked mob maths: the shared "32x Pig Spawner
 * [Normal]" label and the loot splitting for multiplied drops.
 */
final class SpawnerStacksTest {

    @Test
    void stackNameCarriesTheCount() {
        assertEquals("&f32x Pig Spawner &8[&fNormal&8]",
                SpawnerStacks.stackName("Pig", SpawnerVariant.NORMAL, 32));
        assertEquals("&fPig Spawner &8[&fNormal&8]",
                SpawnerStacks.stackName("Pig", SpawnerVariant.NORMAL, 1),
                "single spawners carry no count prefix");
        assertEquals("&f3x Zombie Spawner &8[&dMythic&8]",
                SpawnerStacks.stackName("Zombie", SpawnerVariant.MYTHIC, 3));
        assertEquals("&f2x Pig Spawner &8[&aAdvanced&8]",
                SpawnerStacks.stackName("Pig", SpawnerVariant.ADVANCED, 2));
        assertEquals("&f5x Pig Spawner &8[&6Ancient&8]",
                SpawnerStacks.stackName("Pig", SpawnerVariant.ANCIENT, 5));
    }

    @Test
    void mobNameCarriesTheCount() {
        assertEquals("&f4x Pig", SpawnerStacks.mobName("Pig", 4));
        assertEquals("&f1x Pig", SpawnerStacks.mobName("Pig", 1));
    }

    @Test
    void lootSplitsIntoStackSizedChunks() {
        assertEquals(List.of(64, 36), SpawnerStacks.splitAmounts(100, 64));
        assertEquals(List.of(64, 64, 2), SpawnerStacks.splitAmounts(130, 64));
        assertEquals(List.of(3), SpawnerStacks.splitAmounts(3, 64));
        assertEquals(List.of(2, 2, 2), SpawnerStacks.splitAmounts(6, 2));
        assertEquals(List.of(), SpawnerStacks.splitAmounts(0, 64));
    }

    @Test
    void entryAmountDefaultsToOneAndResistsGarbage() {
        final SpawnerEntry single = new SpawnerEntry("w", 1, 2, 3, "pig",
                SpawnerVariant.NORMAL, java.util.UUID.randomUUID());
        assertEquals(1, single.amount());
        assertEquals(single, single.withAmount(1));
        final SpawnerEntry stacked = single.withAmount(32);
        assertEquals(32, stacked.amount());
        assertEquals(single.key(), stacked.key(), "stacking never changes the block key");
        assertEquals(1, single.withAmount(0).amount(), "amounts below one clamp to one");
        assertEquals(1, single.withAmount(-7).amount());
    }
}
