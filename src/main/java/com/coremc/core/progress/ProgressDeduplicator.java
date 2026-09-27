package com.coremc.core.progress;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes one real player action count exactly once.
 *
 * <p>Mining a single iron block can be seen by the generic block
 * listener, the Mining Cube bridge and an OmniTool hook; all three
 * post the same {@code dedupeKey} (for example {@code
 * mine:coremc_islands:10:64:-4}) and only the first one is accepted.
 * Rate-limited actions (cheap enchant procs) reuse the same map with a
 * much longer window, so spamming the same proc cannot farm
 * progression.</p>
 *
 * <p>The map is bounded and purged as it is used, so it never grows
 * without limit on a busy server. Pure logic — unit-tested with an
 * injected clock.</p>
 */
public final class ProgressDeduplicator {

    /** Default window in which a repeated action id is ignored. */
    public static final long DEFAULT_WINDOW_MILLIS = 4_000L;
    /** Window applied to rate-limited actions (cheap repeatable procs). */
    public static final long RATE_LIMIT_WINDOW_MILLIS = 60_000L;
    /** Hard cap on remembered actions. */
    public static final int MAX_ENTRIES = 8_192;

    private final long windowMillis;
    private final long rateLimitWindowMillis;
    private final Map<String, Long> seen = new LinkedHashMap<>();

    public ProgressDeduplicator() {
        this(DEFAULT_WINDOW_MILLIS, RATE_LIMIT_WINDOW_MILLIS);
    }

    public ProgressDeduplicator(final long windowMillis, final long rateLimitWindowMillis) {
        this.windowMillis = Math.max(0, windowMillis);
        this.rateLimitWindowMillis = Math.max(0, rateLimitWindowMillis);
    }

    /**
     * True when this event should be counted; false when the same
     * action was already recorded inside its window.
     *
     * @param event the event about to be posted
     * @param now   current time in epoch millis
     */
    public boolean accept(final ProgressEvent event, final long now) {
        if (event == null) {
            return false;
        }
        final String key = keyOf(event);
        if (key == null) {
            return true; // nothing stable to deduplicate on
        }
        purge(now);
        final Long previous = seen.get(key);
        final long window = event.action().rateLimited() ? rateLimitWindowMillis : windowMillis;
        if (previous != null && now - previous < window) {
            return false;
        }
        seen.put(key, now);
        if (seen.size() > MAX_ENTRIES) {
            final var iterator = seen.entrySet().iterator();
            while (seen.size() > MAX_ENTRIES && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return true;
    }

    /** Remembered action ids (diagnostics/tests). */
    public int size() {
        return seen.size();
    }

    /** Forgets everything (used by tests and reloads). */
    public void clear() {
        seen.clear();
    }

    private String keyOf(final ProgressEvent event) {
        if (event.action().rateLimited()) {
            // rate-limited procs deduplicate per player + action + subject
            return event.player() + "|" + event.action().id() + "|" + event.key();
        }
        if (event.dedupeKey() == null || event.dedupeKey().isBlank()) {
            return null;
        }
        return event.player() + "|" + event.action().id() + "|" + event.dedupeKey();
    }

    private void purge(final long now) {
        final long oldest = now - Math.max(windowMillis, rateLimitWindowMillis);
        seen.entrySet().removeIf(entry -> entry.getValue() < oldest);
    }
}
