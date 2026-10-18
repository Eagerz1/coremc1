package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class EquipmentSetServiceTest {
    private static final String[] PIECES = {"boots", "leggings", "chestplate", "helmet"};

    private static String[] ids(final String id) {
        return new String[] {id, id, id, id};
    }

    @Test
    void authenticCompleteSetGrantsItsBonus() {
        for (final String role : new String[] {"miner", "farmer", "fisher", "logger", "slayer"}) {
            assertEquals(role, EquipmentSetService.matchingSet(ids(role), PIECES));
        }
    }

    @Test
    void rejectsWrongOrDuplicatePieceIds() {
        assertNull(EquipmentSetService.matchingSet(ids("miner"),
                new String[] {"helmet", "leggings", "chestplate", "boots"}));
        assertNull(EquipmentSetService.matchingSet(ids("miner"),
                new String[] {"boots", "boots", "chestplate", "helmet"}));
        assertNull(EquipmentSetService.matchingSet(ids("miner"),
                new String[] {"boots", "leggings", null, "helmet"}));
    }

    @Test
    void rejectsMixedUnknownOrMissingSetIds() {
        assertNull(EquipmentSetService.matchingSet(
                new String[] {"miner", "miner", "slayer", "miner"}, PIECES));
        assertNull(EquipmentSetService.matchingSet(ids("unknown"), PIECES));
        assertNull(EquipmentSetService.matchingSet(
                new String[] {null, "miner", "miner", "miner"}, PIECES));
    }

    @Test
    void rejectsMalformedArmourArrays() {
        assertNull(EquipmentSetService.matchingSet(null, PIECES));
        assertNull(EquipmentSetService.matchingSet(ids("miner"), null));
        assertNull(EquipmentSetService.matchingSet(ids("miner"), new String[] {"boots"}));
        assertNull(EquipmentSetService.matchingSet(new String[] {"miner"}, PIECES));
    }
}
