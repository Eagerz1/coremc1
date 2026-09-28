package com.coremc.core.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ModerationSnapshotTest {

    @Test
    void roundTripsRecordsCountersAndFreezes() {
        final UUID target = UUID.randomUUID();
        final ActorContext console = new ActorContext(null, "Console", StaffRank.console(), true, true);
        final PunishmentRecord mute = PunishmentRecord.create(PunishmentType.MUTE, target, "Target",
                console, "Reason", "tier", 1, "chat_spam", 25, 1000L, 3L * 24L * 60L * 60L * 1000L);
        final FreezeRecord freeze = FreezeRecord.create(target, "Target", console, "Checking", 2000L);
        final ModerationSnapshot snapshot = new ModerationSnapshot(
                java.util.List.of(mute),
                Map.of(target, Map.of(1, 25, 2, 1)),
                Map.of(target, freeze));

        final ModerationSnapshot loaded = ModerationSnapshot.fromMap(snapshot.toMap());

        assertEquals(1, loaded.records().size());
        assertEquals(PunishmentType.MUTE, loaded.records().getFirst().type());
        assertEquals(25, loaded.records().getFirst().offenseNumber());
        assertEquals(25, loaded.tierCounters().get(target).get(1));
        assertTrue(loaded.activeFreezes().containsKey(target));
    }
}
