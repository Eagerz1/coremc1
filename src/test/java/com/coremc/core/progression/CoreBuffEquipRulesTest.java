package com.coremc.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoreBuffEquipRulesTest {

    @Test
    void firstEquipUsesAvailableSlotAndStartsCooldown() {
        final CoreBuffEquipRules.Result result = CoreBuffEquipRules.toggle(
                Set.of("rich-veins"), Set.of(), "rich-veins", 1,
                0L, 1000L, 12L * 60L * 60L * 1000L);
        assertEquals(CoreBuffEquipRules.Status.EQUIPPED, result.status());
        assertTrue(result.active().contains("rich-veins"));
        assertEquals(1000L, result.lastSwapAt());
    }

    @Test
    void slotLimitRejectsExtraBuffs() {
        final Set<String> active = new LinkedHashSet<>();
        active.add("rich-veins");
        final CoreBuffEquipRules.Result result = CoreBuffEquipRules.toggle(
                Set.of("rich-veins", "deep-waters"), active, "deep-waters", 1,
                0L, 1000L, 0L);
        assertEquals(CoreBuffEquipRules.Status.NO_SLOT, result.status());
        assertEquals(active, result.active());
    }

    @Test
    void swapCooldownRejectsSpamChanges() {
        final CoreBuffEquipRules.Result result = CoreBuffEquipRules.toggle(
                Set.of("rich-veins"), Set.of(), "rich-veins", 1,
                1000L, 2000L, 12L * 60L * 60L * 1000L);
        assertEquals(CoreBuffEquipRules.Status.COOLDOWN, result.status());
    }

    @Test
    void unlockedBuffCanUnequipAfterCooldown() {
        final CoreBuffEquipRules.Result result = CoreBuffEquipRules.toggle(
                Set.of("rich-veins"), Set.of("rich-veins"), "rich-veins", 1,
                1000L, 1000L + 12L * 60L * 60L * 1000L + 1L,
                12L * 60L * 60L * 1000L);
        assertEquals(CoreBuffEquipRules.Status.UNEQUIPPED, result.status());
        assertTrue(result.active().isEmpty());
    }
}
