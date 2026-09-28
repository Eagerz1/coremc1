package com.coremc.core.moderation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable-ish persisted moderation record; revocation fields may be filled once. */
public final class PunishmentRecord {

    private final UUID id;
    private final PunishmentType type;
    private final UUID targetUuid;
    private final String targetName;
    private final UUID actorUuid;
    private final String actorName;
    private final String reason;
    private final String source;
    private final int tier;
    private final String ruleKey;
    private final int offenseNumber;
    private final long createdAtMillis;
    private final long expiresAtMillis;
    private volatile long revokedAtMillis;
    private volatile UUID revokedByUuid;
    private volatile String revokedByName;
    private volatile String revokeReason;

    public PunishmentRecord(
            final UUID id,
            final PunishmentType type,
            final UUID targetUuid,
            final String targetName,
            final UUID actorUuid,
            final String actorName,
            final String reason,
            final String source,
            final int tier,
            final String ruleKey,
            final int offenseNumber,
            final long createdAtMillis,
            final long expiresAtMillis,
            final long revokedAtMillis,
            final UUID revokedByUuid,
            final String revokedByName,
            final String revokeReason) {
        this.id = id;
        this.type = type;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.actorUuid = actorUuid;
        this.actorName = actorName;
        this.reason = reason == null || reason.isBlank() ? "No reason supplied" : reason;
        this.source = source == null || source.isBlank() ? "direct" : source;
        this.tier = tier;
        this.ruleKey = ruleKey == null ? "" : ruleKey;
        this.offenseNumber = offenseNumber;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = Math.max(0L, expiresAtMillis);
        this.revokedAtMillis = Math.max(0L, revokedAtMillis);
        this.revokedByUuid = revokedByUuid;
        this.revokedByName = revokedByName == null ? "" : revokedByName;
        this.revokeReason = revokeReason == null ? "" : revokeReason;
    }

    public static PunishmentRecord create(
            final PunishmentType type,
            final UUID targetUuid,
            final String targetName,
            final ActorContext actor,
            final String reason,
            final String source,
            final int tier,
            final String ruleKey,
            final int offenseNumber,
            final long nowMillis,
            final long durationMillis) {
        final long expires = durationMillis <= 0L ? 0L : nowMillis + durationMillis;
        return new PunishmentRecord(UUID.randomUUID(), type, targetUuid, targetName,
                actor.uuid(), actor.name(), reason, source, tier, ruleKey, offenseNumber,
                nowMillis, expires, 0L, null, "", "");
    }

    public UUID id() {
        return id;
    }

    public PunishmentType type() {
        return type;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
    }

    public UUID actorUuid() {
        return actorUuid;
    }

    public String actorName() {
        return actorName;
    }

    public String reason() {
        return reason;
    }

    public String source() {
        return source;
    }

    public int tier() {
        return tier;
    }

    public String ruleKey() {
        return ruleKey;
    }

    public int offenseNumber() {
        return offenseNumber;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long expiresAtMillis() {
        return expiresAtMillis;
    }

    public long revokedAtMillis() {
        return revokedAtMillis;
    }

    public boolean permanent() {
        return expiresAtMillis == 0L && (type == PunishmentType.MUTE || type == PunishmentType.BAN);
    }

    public boolean activeAt(final long nowMillis) {
        return revokedAtMillis <= 0L && (expiresAtMillis == 0L || expiresAtMillis > nowMillis);
    }

    public void revoke(final ActorContext actor, final String reason, final long nowMillis) {
        this.revokedAtMillis = nowMillis;
        this.revokedByUuid = actor.uuid();
        this.revokedByName = actor.name();
        this.revokeReason = reason == null || reason.isBlank() ? "Revoked by staff" : reason;
    }

    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id.toString());
        map.put("type", type.name());
        map.put("target-uuid", targetUuid.toString());
        map.put("target-name", targetName);
        map.put("actor-uuid", actorUuid == null ? "console" : actorUuid.toString());
        map.put("actor-name", actorName);
        map.put("reason", reason);
        map.put("source", source);
        map.put("tier", tier);
        map.put("rule", ruleKey);
        map.put("offense", offenseNumber);
        map.put("created-at", createdAtMillis);
        map.put("expires-at", expiresAtMillis);
        if (revokedAtMillis > 0L) {
            map.put("revoked-at", revokedAtMillis);
            map.put("revoked-by-uuid", revokedByUuid == null ? "console" : revokedByUuid.toString());
            map.put("revoked-by-name", revokedByName);
            map.put("revoke-reason", revokeReason);
        }
        return map;
    }

    public static PunishmentRecord fromMap(final Map<String, Object> map) {
        return new PunishmentRecord(
                uuid(String.valueOf(map.get("id")), UUID.randomUUID()),
                enumValue(PunishmentType.class, String.valueOf(map.get("type")), PunishmentType.WARN),
                uuid(String.valueOf(map.get("target-uuid")), new UUID(0L, 0L)),
                String.valueOf(map.getOrDefault("target-name", "unknown")),
                nullableUuid(String.valueOf(map.getOrDefault("actor-uuid", "console"))),
                String.valueOf(map.getOrDefault("actor-name", "Console")),
                String.valueOf(map.getOrDefault("reason", "No reason supplied")),
                String.valueOf(map.getOrDefault("source", "direct")),
                intValue(map.get("tier"), 0),
                String.valueOf(map.getOrDefault("rule", "")),
                intValue(map.get("offense"), 0),
                longValue(map.get("created-at"), 0L),
                longValue(map.get("expires-at"), 0L),
                longValue(map.get("revoked-at"), 0L),
                nullableUuid(String.valueOf(map.getOrDefault("revoked-by-uuid", "console"))),
                String.valueOf(map.getOrDefault("revoked-by-name", "")),
                String.valueOf(map.getOrDefault("revoke-reason", "")));
    }

    private static UUID nullableUuid(final String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("console")) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }

    private static UUID uuid(final String raw, final UUID fallback) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException | NullPointerException ignored) {
            return fallback;
        }
    }

    private static int intValue(final Object value, final int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (final NumberFormatException | NullPointerException ignored) {
            return fallback;
        }
    }

    private static long longValue(final Object value, final long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (final NumberFormatException | NullPointerException ignored) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumValue(final Class<E> type, final String raw, final E fallback) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException | NullPointerException ignored) {
            return fallback;
        }
    }
}
