package com.coremc.core.player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent per-player data for CoreMC (schema version 2).
 *
 * A profile is created the first time a player connects and survives
 * server restarts (YAML on disk). It is deliberately designed for
 * expansion: currencies, role progress, OmniTool progress, island
 * association and cosmetic/subscription placeholders all live here so
 * every CoreMC system reads/writes one authoritative record.
 *
 * Mutations happen on the main server thread (join/quit) or from
 * services holding the profile while the player is online; the YAML
 * snapshot is taken at save time. Offline-profile mutations (admin
 * commands) are loaded, mutated and flushed without being cached.
 *
 * Loading is forward/backward tolerant: unknown/missing fields fall
 * back to defaults, so v1 files seamlessly become v2.
 */
public final class PlayerProfile {

    /** Current on-disk schema version. */
    public static final int SCHEMA_VERSION = 2;

    private final UUID uuid;

    private String username;
    private long firstJoinMillis;
    private long lastSeenMillis;
    private long totalLogins;

    // --- currencies (long minor balance; negatives are impossible by contract) ---
    private long money;
    private long credits;
    private long skyTokens;

    // --- progression ---
    private String roleId = "none";
    private int roleLevel = 1;
    private long roleXp;
    private int omniToolLevel = 1;
    private long omniToolXp;

    // --- island association (islandId or null; authoritative membership lives in the island file) ---
    private UUID islandId;

    // --- placeholders for future systems (persisted so nothing is lost later) ---
    private final Map<String, String> cosmetics = new LinkedHashMap<>();
    private String subscriptionTier = "none";
    private long subscriptionExpiresMillis;

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

    /** Rebuilds a profile from its YAML representation (tolerant of v1 files). */
    public static PlayerProfile fromMap(final UUID uuid, final Map<String, Object> map) {
        final PlayerProfile profile = new PlayerProfile(uuid);
        profile.username = String.valueOf(map.getOrDefault("username", "unknown"));
        profile.firstJoinMillis = asLong(map.get("first-join-millis"), 0L);
        profile.lastJoinDefaults(map, profile);
        return profile;
    }

    private void lastJoinDefaults(final Map<String, Object> map, final PlayerProfile profile) {
        profile.lastSeenMillis = asLong(map.get("last-seen-millis"), 0L);
        profile.totalLogins = asLong(map.get("total-logins"), 0L);
        profile.money = Math.max(0L, asLong(map.get("money"), 0L));
        profile.credits = Math.max(0L, asLong(map.get("credits"), 0L));
        profile.skyTokens = Math.max(0L, asLong(map.get("sky-tokens"), 0L));
        profile.roleId = String.valueOf(map.getOrDefault("role", "none"));
        profile.roleLevel = (int) Math.max(1L, asLong(map.get("role-level"), 1L));
        profile.roleXp = Math.max(0L, asLong(map.get("role-xp"), 0L));
        profile.omniToolLevel = (int) Math.max(1L, asLong(map.get("omnitool-level"), 1L));
        profile.omniToolXp = Math.max(0L, asLong(map.get("omnitool-xp"), 0L));
        final Object island = map.get("island-id");
        profile.islandId = island == null ? null : UUID.fromString(String.valueOf(island));
        profile.subscriptionTier = String.valueOf(map.getOrDefault("subscription-tier", "none"));
        profile.subscriptionExpiresMillis = Math.max(0L, asLong(map.get("subscription-expires-millis"), 0L));
        final Object cosmeticsMap = map.get("cosmetics");
        if (cosmeticsMap instanceof Map<?, ?> raw) {
            for (final Map.Entry<?, ?> entry : raw.entrySet()) {
                profile.cosmetics.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
    }

    /** Serialises this profile to a YAML-safe map. */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema-version", SCHEMA_VERSION);
        map.put("username", username);
        map.put("first-join-millis", firstJoinMillis);
        map.put("last-seen-millis", lastSeenMillis);
        map.put("total-logins", totalLogins);
        map.put("money", money);
        map.put("credits", credits);
        map.put("sky-tokens", skyTokens);
        map.put("role", roleId);
        map.put("role-level", roleLevel);
        map.put("role-xp", roleXp);
        map.put("omnitool-level", omniToolLevel);
        map.put("omnitool-xp", omniToolXp);
        map.put("island-id", islandId == null ? null : islandId.toString());
        map.put("subscription-tier", subscriptionTier);
        map.put("subscription-expires-millis", subscriptionExpiresMillis);
        map.put("cosmetics", new LinkedHashMap<>(cosmetics));
        return map;
    }

    /** Records a successful login. Called on the main thread from the join listener. */
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

    // --- currency accessors; the service layer enforces the no-negative/overflow contract ---

    public long money() {
        return money;
    }

    public long credits() {
        return credits;
    }

    public long skyTokens() {
        return skyTokens;
    }

    public long balanceOf(final com.coremc.core.economy.Currency currency) {
        return switch (currency) {
            case MONEY -> money;
            case CREDITS -> credits;
            case SKY_TOKENS -> skyTokens;
        };
    }

    /** Package-visible low-level setter — ALWAYS go through EconomyService. */
    public void setBalanceInternal(final com.coremc.core.economy.Currency currency, final long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("Balance cannot be negative: " + amount);
        }
        switch (currency) {
            case MONEY -> money = amount;
            case CREDITS -> credits = amount;
            case SKY_TOKENS -> skyTokens = amount;
        }
    }

    // --- progression accessors ---

    public String roleId() {
        return roleId;
    }

    public int roleLevel() {
        return roleLevel;
    }

    public long roleXp() {
        return roleXp;
    }

    public int omniToolLevel() {
        return omniToolLevel;
    }

    public long omniToolXp() {
        return omniToolXp;
    }

    /** Island this player owns or belongs to (null = none). Fast redirect only;
     * team membership authority is the island file. */
    public UUID islandId() {
        return islandId;
    }

    public void islandId(final UUID islandId) {
        this.islandId = islandId;
    }

    public Map<String, String> cosmetics() {
        return Map.copyOf(cosmetics);
    }

    public String subscriptionTier() {
        return subscriptionTier;
    }

    public long subscriptionExpiresMillis() {
        return subscriptionExpiresMillis;
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
        return "PlayerProfile{uuid=" + uuid + ", username=" + username + ", logins=" + totalLogins + ", credits="
                + credits + ", skyTokens=" + skyTokens + "}";
    }
}
