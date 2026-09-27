package com.coremc.core.market;

/**
 * Wall-clock schedule maths for the limited-stock market. Everything
 * works on persisted epoch-millis timestamps — never uptime — so a
 * restart lands exactly where the clock says:
 *
 * <ul>
 *   <li>openings sit on a fixed grid: {@code anchor + n * openEvery}
 *       (the anchor is midnight UTC plus a configurable offset, so
 *       openings can dodge on-the-hour event announcements),</li>
 *   <li>each opening lasts {@code openFor},</li>
 *   <li>a restart inside an open window keeps the SAME window (and
 *       the persisted rotation); a restart after the window closes
 *       simply waits for the next grid slot.</li>
 * </ul>
 */
public final class MarketSchedule {

    private final long openEveryMillis;
    private final long openForMillis;
    private final long anchorOffsetMillis;

    public MarketSchedule(final long openEveryMillis, final long openForMillis,
                          final long anchorOffsetMillis) {
        if (openEveryMillis <= 0 || openForMillis <= 0 || openForMillis >= openEveryMillis) {
            throw new IllegalArgumentException("market schedule: 0 < duration < interval required");
        }
        this.openEveryMillis = openEveryMillis;
        this.openForMillis = openForMillis;
        this.anchorOffsetMillis = Math.max(0, anchorOffsetMillis) % openEveryMillis;
    }

    public long openEveryMillis() {
        return openEveryMillis;
    }

    public long openForMillis() {
        return openForMillis;
    }

    /** The grid opening at or before {@code now}. */
    public long currentSlotStart(final long now) {
        final long shifted = now - anchorOffsetMillis;
        final long slot = Math.floorDiv(shifted, openEveryMillis);
        return slot * openEveryMillis + anchorOffsetMillis;
    }

    /** True when the grid window containing {@code now} is open. */
    public boolean isOpenWindow(final long now) {
        return now < currentSlotStart(now) + openForMillis;
    }

    /** When the window that contains/precedes {@code now} closes. */
    public long closeAt(final long now) {
        return currentSlotStart(now) + openForMillis;
    }

    /** The next grid opening strictly after {@code now}'s slot start. */
    public long nextOpenAt(final long now) {
        return currentSlotStart(now) + openEveryMillis;
    }

    /**
     * The opening the market should treat as "due" at {@code now}:
     * the current slot while its window is still open, otherwise the
     * next slot.
     */
    public long dueOpenAt(final long now) {
        return isOpenWindow(now) ? currentSlotStart(now) : nextOpenAt(now);
    }

    /**
     * A stable rotation id for a grid opening — restarting inside the
     * same window recreates the SAME id, which is how a restart knows
     * to keep the persisted rotation instead of rolling a duplicate.
     */
    public String rotationId(final long openAt) {
        return "rot-" + openAt;
    }
}
