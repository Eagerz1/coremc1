package com.coremc.core.moderation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Active or historical freeze state. Active freezes also get an audit record. */
public final class FreezeRecord {

    private final UUID targetUuid;
    private final String targetName;
    private final UUID actorUuid;
    private final String actorName;
    private final String reason;
    private final long createdAtMillis;

    public FreezeRecord(
            final UUID targetUuid,
            final String targetName,
            final UUID actorUuid,
            final String actorName,
            final String reason,
            final long createdAtMillis) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.actorUuid = actorUuid;
        this.actorName = actorName;
        this.reason = reason == null || reason.isBlank() ? "No reason supplied" : reason;
        this.createdAtMillis = createdAtMillis;
    }

    public static FreezeRecord create(
            final UUID targetUuid, final String targetName, final ActorContext actor, final String reason,
            final long nowMillis) {
        return new FreezeRecord(targetUuid, targetName, actor.uuid(), actor.name(), reason, nowMillis);
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

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("target-uuid", targetUuid.toString());
        map.put("target-name", targetName);
        map.put("actor-uuid", actorUuid == null ? "console" : actorUuid.toString());
        map.put("actor-name", actorName);
        map.put("reason", reason);
        map.put("created-at", createdAtMillis);
        return map;
    }

    public static FreezeRecord fromMap(final Map<String, Object> map) {
        return new FreezeRecord(
                uuid(String.valueOf(map.get("target-uuid")), new UUID(0L, 0L)),
                String.valueOf(map.getOrDefault("target-name", "unknown")),
                nullableUuid(String.valueOf(map.getOrDefault("actor-uuid", "console"))),
                String.valueOf(map.getOrDefault("actor-name", "Console")),
                String.valueOf(map.getOrDefault("reason", "No reason supplied")),
                longValue(map.get("created-at"), 0L));
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
}
