package com.coremc.core.quest;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestRotationTest {

    @Test
    void dailyAssignmentIsStableUniqueAndPlayerSpecific() {
        final List<String> ids = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        final UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        final List<String> one = QuestRotation.select(ids, first, "2026-10-04", 3);
        assertEquals(one, QuestRotation.select(ids, first, "2026-10-04", 3));
        assertEquals(3, one.size());
        assertEquals(3, one.stream().distinct().count());
        assertFalse(one.equals(QuestRotation.select(ids, second, "2026-10-04", 3)));
    }

    @Test
    void progressCapsAtTargetAndCannotMoveAfterClaim() {
        final QuestRotation.Progress start = new QuestRotation.Progress(9, false, false);
        final QuestRotation.Progress done = QuestRotation.advance(start, 5, 10);
        assertEquals(10L, done.amount());
        assertTrue(done.done());
        assertEquals(new QuestRotation.Progress(10, true, true), QuestRotation.advance(
                new QuestRotation.Progress(10, true, true), 100, 10));
    }
}
