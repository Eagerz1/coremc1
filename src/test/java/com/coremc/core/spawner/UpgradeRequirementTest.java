package com.coremc.core.spawner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.essence.EssenceType;
import org.junit.jupiter.api.Test;

/**
 * UpgradeRequirement: the typed building block of upgrade costs —
 * factories, parsing keys, and the consumed() rule (kills are a
 * threshold, everything else is spent).
 */
class UpgradeRequirementTest {

    @Test
    void factoriesBuildTheRightShapes() {
        final UpgradeRequirement money = UpgradeRequirement.money(5000);
        assertEquals(UpgradeRequirement.Type.MONEY, money.type());
        assertNull(money.essence());
        assertNull(money.mobId());
        assertEquals(5000, money.amount());

        final UpgradeRequirement kills = UpgradeRequirement.kills(10000);
        assertEquals(UpgradeRequirement.Type.KILLS, kills.type());

        final UpgradeRequirement essence = UpgradeRequirement.essence(EssenceType.SLAYER, 20);
        assertEquals(UpgradeRequirement.Type.ESSENCE, essence.type());
        assertEquals(EssenceType.SLAYER, essence.essence());

        final UpgradeRequirement ownDrop = UpgradeRequirement.drop(null, 3);
        assertEquals(UpgradeRequirement.Type.DROP, ownDrop.type());
        assertNull(ownDrop.mobId());
        final UpgradeRequirement otherDrop = UpgradeRequirement.drop("cow", 5);
        assertEquals("cow", otherDrop.mobId());
    }

    @Test
    void typesParseCaseInsensitively() {
        assertEquals(UpgradeRequirement.Type.MONEY, UpgradeRequirement.Type.of("money"));
        assertEquals(UpgradeRequirement.Type.KILLS, UpgradeRequirement.Type.of("Kills"));
        assertEquals(UpgradeRequirement.Type.ESSENCE, UpgradeRequirement.Type.of("essence"));
        assertEquals(UpgradeRequirement.Type.DROP, UpgradeRequirement.Type.of(" drop "));
        assertNull(UpgradeRequirement.Type.of("blood"));
        assertNull(UpgradeRequirement.Type.of(null));
    }

    @Test
    void onlyKillsAreAThresholdEverythingElseIsSpent() {
        assertFalse(UpgradeRequirement.kills(100).consumed(), "kills are never spent");
        assertTrue(UpgradeRequirement.money(1).consumed());
        assertTrue(UpgradeRequirement.essence(EssenceType.MINING, 1).consumed());
        assertTrue(UpgradeRequirement.drop(null, 1).consumed());
    }

    @Test
    void wholeAmountRoundsForDisplay() {
        assertEquals(25, UpgradeRequirement.essence(EssenceType.SLAYER, 25).wholeAmount());
        assertEquals(3, UpgradeRequirement.drop(null, 3).wholeAmount());
    }
}
