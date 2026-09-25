package com.coremc.core.essence;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Virtual essence balances: Slayer, Mining and Farming, plus each
 * player's lifetime mob-kill count (a threshold used by spawner
 * upgrade requirements — it is never spent).
 *
 * <p>All access happens on the main thread; every mutation is written
 * through to the store immediately (the file is tiny, so durability
 * beats batching — same policy as the coin economy). Balances are
 * never negative.</p>
 */
public final class EssenceManager {

    private final EssenceStore store;
    private final Logger logger;
    private final Map<UUID, EssenceProfile> profiles = new LinkedHashMap<>();

    public EssenceManager(final EssenceStore store, final Logger logger) throws IOException {
        this.store = store;
        this.logger = logger;
        this.profiles.putAll(store.loadAll());
    }

    // ------------------------------------------------------------------
    // Balances
    // ------------------------------------------------------------------

    /** Current balance of one essence type (0 for unknown players). */
    public long balance(final UUID player, final EssenceType type) {
        final EssenceProfile profile = profiles.get(player);
        return profile == null ? 0L : profile.get(type);
    }

    /** Sum of all three essence types. */
    public long total(final UUID player) {
        long sum = 0;
        for (final EssenceType type : EssenceType.values()) {
            sum += balance(player, type);
        }
        return sum;
    }

    /** Adds essence (amounts &le; 0 are ignored). */
    public void give(final UUID player, final EssenceType type, final long amount) {
        if (amount <= 0) {
            return;
        }
        profile(player).set(type, profile(player).get(type) + amount);
        persist();
    }

    /**
     * Removes essence. Returns false (and changes nothing) when the
     * player cannot afford it — balances are never negative.
     */
    public boolean take(final UUID player, final EssenceType type, final long amount) {
        if (amount <= 0) {
            return true;
        }
        final EssenceProfile profile = profile(player);
        final long held = profile.get(type);
        if (held < amount) {
            return false;
        }
        profile.set(type, held - amount);
        persist();
        return true;
    }

    /** Sets an exact balance (negative values clamp to zero). */
    public void set(final UUID player, final EssenceType type, final long amount) {
        profile(player).set(type, Math.max(0, amount));
        persist();
    }

    // ------------------------------------------------------------------
    // Lifetime mob kills (requirement threshold, never spent)
    // ------------------------------------------------------------------

    /** The player's lifetime mob-kill count. */
    public long kills(final UUID player) {
        final EssenceProfile profile = profiles.get(player);
        return profile == null ? 0L : profile.kills;
    }

    /** Adds kills (amounts &le; 0 are ignored). */
    public void addKills(final UUID player, final long amount) {
        if (amount <= 0) {
            return;
        }
        profile(player).kills += amount;
        persist();
    }

    /** Sets the lifetime kill count (negative values clamp to zero). */
    public void setKills(final UUID player, final long amount) {
        profile(player).kills = Math.max(0, amount);
        persist();
    }

    // ------------------------------------------------------------------
    // misc
    // ------------------------------------------------------------------

    private EssenceProfile profile(final UUID player) {
        return profiles.computeIfAbsent(player, ignored -> new EssenceProfile());
    }

    private void persist() {
        try {
            store.saveAll(profiles);
        } catch (final IOException exception) {
            logger.severe("Could not save essences: " + exception.getMessage());
        }
    }

    /** Flushes the store (called from onDisable). */
    public void shutdown() {
        persist();
    }

    /** Grouped digits, e.g. {@code 1250 -> "1,250"}. */
    public static String format(final long value) {
        return String.format("%,d", value);
    }
}
