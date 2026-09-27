package com.coremc.core.progress;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One authoritative progression fact: "this player did this thing,
 * this many times, from this source".
 *
 * <p>Events are immutable value objects so they can be posted, logged
 * and unit-tested without a server. The {@code key} is a stable id
 * (material name, generator id, mob id, discovery id, …) always stored
 * lower-case; the {@code dedupeKey} identifies the underlying real
 * world action so the same action posted from two bridges is only ever
 * counted once (see {@link ProgressDeduplicator}).</p>
 *
 * @param player     the player the progress belongs to
 * @param action     what happened
 * @param key        stable subject id, lower-case (never a display name)
 * @param amount     how much (always &ge; 1 for counted actions)
 * @param source     where the event came from
 * @param dedupeKey  stable id of the real action, or null to skip dedupe
 * @param timestamp  epoch millis
 */
public record ProgressEvent(UUID player, ProgressAction action, String key, long amount,
                            ProgressSource source, String dedupeKey, long timestamp) {

    public ProgressEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(source, "source");
        key = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        amount = Math.max(0, amount);
    }

    /** An event with an explicit dedupe key (the usual case for world actions). */
    public static ProgressEvent of(final UUID player, final ProgressAction action, final String key,
                                   final long amount, final ProgressSource source,
                                   final String dedupeKey) {
        return new ProgressEvent(player, action, key, amount, source, dedupeKey,
                System.currentTimeMillis());
    }

    /** An event that cannot be deduplicated (already unique by construction). */
    public static ProgressEvent of(final UUID player, final ProgressAction action, final String key,
                                   final long amount, final ProgressSource source) {
        return of(player, action, key, amount, source, null);
    }

    /** A single-count event, e.g. one discovery or one upgrade. */
    public static ProgressEvent single(final UUID player, final ProgressAction action,
                                       final String key, final ProgressSource source,
                                       final String dedupeKey) {
        return of(player, action, key, 1, source, dedupeKey);
    }

    /** The same event with a different amount (used by weighting). */
    public ProgressEvent withAmount(final long newAmount) {
        return new ProgressEvent(player, action, key, newAmount, source, dedupeKey, timestamp);
    }

    /** True when this event carries no countable amount. */
    public boolean empty() {
        return amount <= 0;
    }
}
