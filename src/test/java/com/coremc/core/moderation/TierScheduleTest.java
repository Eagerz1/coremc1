package com.coremc.core.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TierScheduleTest {

    @Test
    void t1BoundaryDurationsMatchReference() {
        final TierSchedule t1 = TierSchedule.defaultFor(1);
        assertEquals(ModerationAction.MUTE, t1.actionFor(24).action());
        assertEquals("1d", t1.actionFor(24).durationText());
        assertEquals("3d", t1.actionFor(25).durationText());
        assertEquals("3d", t1.actionFor(29).durationText());
        assertEquals("7d", t1.actionFor(30).durationText());
        assertEquals("7d", t1.actionFor(39).durationText());
        assertEquals("30d", t1.actionFor(40).durationText());
        assertEquals("30d", t1.actionFor(49).durationText());
        assertEquals("permanent", t1.actionFor(50).durationText());
        assertEquals("permanent", t1.actionFor(80).durationText());
    }

    @Test
    void t2CapsAtThirtyDayBans() {
        final TierSchedule t2 = TierSchedule.defaultFor(2);
        assertEquals(ModerationAction.WARN, t2.actionFor(1).action());
        assertEquals("1d", t2.actionFor(2).durationText());
        assertEquals("3d", t2.actionFor(3).durationText());
        assertEquals("7d", t2.actionFor(4).durationText());
        assertEquals("14d", t2.actionFor(5).durationText());
        assertEquals("30d", t2.actionFor(6).durationText());
        assertEquals("30d", t2.actionFor(60).durationText());
    }

    @Test
    void higherTiersEscalateToPermanent() {
        assertEquals("permanent", TierSchedule.defaultFor(3).actionFor(4).durationText());
        assertEquals("permanent", TierSchedule.defaultFor(4).actionFor(3).durationText());
        assertEquals("permanent", TierSchedule.defaultFor(5).actionFor(1).durationText());
    }
}
