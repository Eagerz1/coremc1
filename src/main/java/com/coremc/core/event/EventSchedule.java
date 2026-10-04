package com.coremc.core.event;

/** Pure schedule math for the recurring one-hour CoreMC event window. */
public final class EventSchedule {

    private EventSchedule() {}

    public static Window at(final long nowMillis, final long anchorMillis,
            final long intervalMillis, final long durationMillis) {
        if (intervalMillis <= 0L || durationMillis <= 0L || durationMillis > intervalMillis) {
            throw new IllegalArgumentException("event duration must be positive and no longer than its interval");
        }
        if (nowMillis < anchorMillis) {
            return new Window(false, anchorMillis, anchorMillis + durationMillis, anchorMillis);
        }
        final long cycle = Math.floorDiv(nowMillis - anchorMillis, intervalMillis);
        final long start = anchorMillis + cycle * intervalMillis;
        final long end = start + durationMillis;
        final boolean active = nowMillis < end;
        return new Window(active, start, end, active ? start : start + intervalMillis);
    }

    public record Window(boolean active, long startMillis, long endMillis, long nextStartMillis) {
        public long remainingMillis(final long nowMillis) {
            return active ? Math.max(0L, endMillis - nowMillis) : Math.max(0L, nextStartMillis - nowMillis);
        }

        /** Four clear progress steps, used in the event boss-bar title. */
        public int completedSegments(final long nowMillis) {
            if (!active || endMillis <= startMillis) return 0;
            final long elapsed = Math.max(0L, Math.min(endMillis - startMillis, nowMillis - startMillis));
            return (int) Math.min(4L, elapsed * 4L / (endMillis - startMillis));
        }

        public double bossBarProgress(final long nowMillis) {
            if (!active || endMillis <= startMillis) return 0.0;
            return Math.max(0.25, 1.0 - completedSegments(nowMillis) / 4.0);
        }
    }
}
