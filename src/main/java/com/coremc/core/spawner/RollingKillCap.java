package com.coremc.core.spawner;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Anti-farming guard for spawner unlock progression: at most {@code cap}
 * kills are counted inside each rolling one-minute window per player.
 * Pure Java (no server runtime) so the timing rules are unit-testable.
 *
 * A cap of {@code 0} disables the guard (every kill counts) — this is the
 * "off" value in config (clamp is applied in {@code CoreConfig}).
 */
public final class RollingKillCap {

    private static final long WINDOW_MILLIS = 60_000L;

    private final long cap;
    private final Deque<Long> admits = new ArrayDeque<>();

    public RollingKillCap(final long cap) {
        this.cap = Math.max(0L, cap);
    }

    /**
     * Records an attempt at {@code nowMillis}; returns true when the kill
     * COUNTS towards unlock progression (window had capacity left).
     * The deque never grows past {@code cap} entries per player, so the
     * memory bound is the configured cap itself.
     */
    public boolean tryCount(final long nowMillis) {
        if (cap <= 0L) {
            return true; // guard disabled
        }
        expireBefore(nowMillis);
        if (admits.size() >= cap) {
            return false;
        }
        admits.addLast(nowMillis);
        return true;
    }

    /** Drops window entries older than one minute relative to {@code nowMillis}. */
    public void expireBefore(final long nowMillis) {
        while (!admits.isEmpty() && nowMillis - admits.peekFirst() >= WINDOW_MILLIS) {
            admits.pollFirst();
        }
    }
}
