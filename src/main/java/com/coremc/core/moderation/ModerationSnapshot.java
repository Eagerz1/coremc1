package com.coremc.core.moderation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Complete moderation state persisted atomically to one YAML file. */
public record ModerationSnapshot(
        List<PunishmentRecord> records,
        Map<UUID, Map<Integer, Integer>> tierCounters,
        Map<UUID, FreezeRecord> activeFreezes) {

    public ModerationSnapshot {
        records = List.copyOf(records == null ? List.of() : records);
        tierCounters = copyCounters(tierCounters);
        activeFreezes = Map.copyOf(activeFreezes == null ? Map.of() : activeFreezes);
    }

    private static Map<UUID, Map<Integer, Integer>> copyCounters(final Map<UUID, Map<Integer, Integer>> source) {
        final Map<UUID, Map<Integer, Integer>> copy = new LinkedHashMap<>();
        if (source != null) {
            for (final Map.Entry<UUID, Map<Integer, Integer>> entry : source.entrySet()) {
                copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
        }
        return Map.copyOf(copy);
    }

    public static ModerationSnapshot empty() {
        return new ModerationSnapshot(List.of(), Map.of(), Map.of());
    }

    public Map<String, Object> toMap() {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema-version", 1);
        final List<Map<String, Object>> recordList = new ArrayList<>();
        for (final PunishmentRecord record : records) {
            recordList.add(record.toMap());
        }
        root.put("records", recordList);
        final Map<String, Object> counters = new LinkedHashMap<>();
        for (final Map.Entry<UUID, Map<Integer, Integer>> player : tierCounters.entrySet()) {
            final Map<String, Object> tiers = new LinkedHashMap<>();
            for (final Map.Entry<Integer, Integer> tier : player.getValue().entrySet()) {
                tiers.put(String.valueOf(tier.getKey()), tier.getValue());
            }
            counters.put(player.getKey().toString(), tiers);
        }
        root.put("tier-counters", counters);
        final List<Map<String, Object>> freezes = new ArrayList<>();
        for (final FreezeRecord freeze : activeFreezes.values()) {
            freezes.add(freeze.toMap());
        }
        root.put("active-freezes", freezes);
        return root;
    }

    @SuppressWarnings("unchecked")
    public static ModerationSnapshot fromMap(final Map<String, Object> root) {
        final List<PunishmentRecord> records = new ArrayList<>();
        final Object recordObject = root.get("records");
        if (recordObject instanceof List<?> list) {
            for (final Object entry : list) {
                if (entry instanceof Map<?, ?> raw) {
                    records.add(PunishmentRecord.fromMap((Map<String, Object>) (Map<?, ?>) raw));
                }
            }
        }

        final Map<UUID, Map<Integer, Integer>> counters = new LinkedHashMap<>();
        final Object counterObject = root.get("tier-counters");
        if (counterObject instanceof Map<?, ?> rawCounters) {
            for (final Map.Entry<?, ?> player : rawCounters.entrySet()) {
                final UUID uuid = uuid(String.valueOf(player.getKey()));
                if (uuid == null || !(player.getValue() instanceof Map<?, ?> rawTiers)) {
                    continue;
                }
                final Map<Integer, Integer> tierMap = new LinkedHashMap<>();
                for (final Map.Entry<?, ?> tier : rawTiers.entrySet()) {
                    try {
                        tierMap.put(Integer.parseInt(String.valueOf(tier.getKey())), intValue(tier.getValue(), 0));
                    } catch (final NumberFormatException ignored) {
                        // skip corrupt counter key
                    }
                }
                counters.put(uuid, tierMap);
            }
        }

        final Map<UUID, FreezeRecord> freezes = new LinkedHashMap<>();
        final Object freezeObject = root.get("active-freezes");
        if (freezeObject instanceof List<?> list) {
            for (final Object entry : list) {
                if (entry instanceof Map<?, ?> raw) {
                    final FreezeRecord freeze = FreezeRecord.fromMap((Map<String, Object>) (Map<?, ?>) raw);
                    freezes.put(freeze.targetUuid(), freeze);
                }
            }
        }
        return new ModerationSnapshot(records, counters, freezes);
    }

    private static UUID uuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException | NullPointerException ignored) {
            return null;
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
}
