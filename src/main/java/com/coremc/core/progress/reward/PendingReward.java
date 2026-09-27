package com.coremc.core.progress.reward;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A reward that was earned but could not be handed over yet — a full
 * inventory, or a currency whose system lives on another branch.
 *
 * <p>Nothing valuable is ever dropped on the floor: it is written to
 * {@code pending-rewards.yml} and delivered on the next join or claim.
 * Each record carries a unique id so delivery is exactly once.</p>
 */
public record PendingReward(String id, UUID player, RewardType type, String subject, long amount,
                            String source, long createdAt) {

    public PendingReward {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(type, "type");
        subject = subject == null ? "" : subject.trim().toLowerCase(Locale.ROOT);
        amount = Math.max(0, amount);
        source = source == null ? "" : source;
    }

    /** A fresh record with a generated id. */
    public static PendingReward of(final UUID player, final Reward reward, final String source) {
        return new PendingReward(UUID.randomUUID().toString(), player, reward.type(), reward.id(),
                reward.amount(), source, System.currentTimeMillis());
    }

    /** The reward this record represents. */
    public Reward reward() {
        return new Reward(type, subject, amount, "");
    }

    /** Flat map for YAML storage. */
    public Map<String, Object> toMap() {
        return Map.of(
                "id", id,
                "type", type.id(),
                "subject", subject,
                "amount", amount,
                "source", source,
                "created", createdAt);
    }

    /** Reads a stored record; returns null when the entry is unusable. */
    public static PendingReward fromMap(final UUID player, final Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        final Object rawId = map.get("id");
        final RewardType type = RewardType.of(String.valueOf(map.get("type")));
        if (rawId == null || type == null) {
            return null;
        }
        long amount = 0;
        if (map.get("amount") instanceof Number number) {
            amount = number.longValue();
        }
        long created = 0;
        if (map.get("created") instanceof Number number) {
            created = number.longValue();
        }
        return new PendingReward(String.valueOf(rawId), player, type,
                map.get("subject") == null ? "" : String.valueOf(map.get("subject")), amount,
                map.get("source") == null ? "" : String.valueOf(map.get("source")), created);
    }
}
