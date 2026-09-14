package com.coremc.core.island;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending island invites: one outstanding invite per target player,
 * expiring after a configurable window. Pure data structure — expiry
 * checks are made by the caller against its own clock.
 */
public final class InviteLedger {

    /** An outstanding invite to the island owned by {@code islandOwner}. */
    public record Entry(UUID islandOwner, String inviterName, long expiresAtMillis) {

        public boolean expired(final long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }

    private final Map<UUID, Entry> invites = new ConcurrentHashMap<>();

    public void add(final UUID target, final Entry entry) {
        invites.put(target, entry);
    }

    /** The raw invite for the target, expired or not (may be null). */
    public Entry get(final UUID target) {
        return invites.get(target);
    }

    public void remove(final UUID target) {
        invites.remove(target);
    }

    /** Drops every pending invite pointing at the given island. */
    public void removeAllFor(final UUID islandOwner) {
        invites.values().removeIf(entry -> entry.islandOwner().equals(islandOwner));
    }

    /** Drops every expired entry. */
    public void purge(final long nowMillis) {
        invites.values().removeIf(entry -> entry.expired(nowMillis));
    }

    public int size() {
        return invites.size();
    }
}
