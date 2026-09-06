package com.coremc.core.player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent per-player data for CoreMC.
 *
 * A profile is created the first time a player connects and survives
 * server restarts (YAML on disk). Profiles are mutated on the main
 * server thread (join/quit) and serialised from a defensive
 * {@link #toMap() snapshot} when saving.
 *
 * Instances are confined to {@link PlayerDataService}; nothing outside
 * that service may retain a reference long-term.
 */
public final class PlayerProfile {

    private final UUID uuid;

    private String username;
    private long firstJoinMillis;
    private long lastSeenMillis;
    private long totalLogins;

    private PlayerProfile(final UUID uuid) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
    }

    /** Creates a brand-new profile for a player who has never joined. */
    public static PlayerProfile createNew(final UUID uuid, final String username, final long nowMillis) {
        final PlayerProfile profile = new PlayerProfile(uuid);
        profile.username = username;
        profile.firstJoinMillis = nowMillis;
        profile.lastSeenMillis = nowMillis;
        profile.totalLogins = 0L;
        return profile;
    }

    /** Rebuilds a profile from its YAML representation. */
    public static PlayerProfile fromMap(final UUID uuid, final Map<String, Object> map) {
        final PlayerProfile profile = new PlayerProfile(uuid);
        profile.username = String.valueOf(map.getOrDefault("username", "unknown"));
        profile.firstJoinMillis = asLong(map.get("first-join-millis"), 0L);
        profile.lastSeenMillis = asLong(map.get("last-seen-millis"), 0L);
        profile.totalLogins = asLong(map.get("total-logins"), 0L);
        return profile;
    }

    /** Serialises this profile to a YAML-safe map. */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("username", username);
        map.put("first-join-millis", firstJoinMillis);
        map.put("last-seen-millis", lastSeenMillis);
        map.put("total-logins", totalLogins);
        return map;
    }

    /**
     * Records a successful login. Called on the main thread from the
     * join listener.
     */
    public void recordLogin(final String currentName, final long nowMillis) {
        this.username = currentName;
        this.lastSeenMillis = nowMillis;
        this.totalLogins++;
    }

    /** Updates the last-seen timestamp (called when the player quits). */
    public void recordQuit(final long nowMillis) {
        this.lastSeenMillis = nowMillis;
    }

    /** Whether this login is the player's first ever. Call before/for {@link #recordLogin}. */
    public boolean isFirstLogin() {
        return totalLogins == 0L;
    }

    public UUID uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    public long firstJoinMillis() {
        return firstJoinMillis;
    }

    public long lastSeenMillis() {
        return lastSeenMillis;
    }

    public long totalLogins() {
        return totalLogins;
    }

    private static long asLong(final Object value, final long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @Override
    public String toString() {
        return "PlayerProfile{uuid=" + uuid + ", username=" + username + ", logins=" + totalLogins + "}";
    }
}
