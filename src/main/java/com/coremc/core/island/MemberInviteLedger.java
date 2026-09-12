package com.coremc.core.island;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory ledger of pending island invites, keyed by INVITEE.
 *
 * Invites deliberately do not persist across restarts (standard
 * Minecraft-server behaviour) and expire lazily on touch — no scheduled
 * cleanup task, so nothing can leak.
 */
final class MemberInviteLedger {

    record Pending(UUID islandId, UUID inviter, long expiresAtMillis) {
    }

    private final Map<UUID, Pending> byInvitee = new ConcurrentHashMap<>();

    void invite(final UUID invitee, final UUID islandId, final UUID inviter, final long expiresAtMillis) {
        byInvitee.put(invitee, new Pending(islandId, inviter, expiresAtMillis));
    }

    /** Returns and removes the invitee's invite, if any (also removes expired ones). */
    Pending take(final UUID invitee) {
        final Pending pending = byInvitee.remove(invitee);
        if (pending == null) {
            return null;
        }
        return pending;
    }

    Pending peek(final UUID invitee) {
        final Pending pending = byInvitee.get(invitee);
        if (pending != null && pending.expiresAtMillis() < System.currentTimeMillis()) {
            byInvitee.remove(invitee, pending);
            return null;
        }
        return pending;
    }

    /** Removes every invite pointing at an island (deletion) or any invite involving a player. */
    void purgeIsland(final UUID islandId) {
        byInvitee.values().removeIf(pending -> pending.islandId().equals(islandId));
    }

    void clear() {
        byInvitee.clear();
    }
}
