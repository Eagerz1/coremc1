package com.coremc.core.quest;

import com.coremc.core.island.Island;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/** A meaningful gameplay action published into the central quest pipeline. */
public record QuestAction(UUID playerId, Island island, String action, long amount, Map<String, String> metadata) {

    public QuestAction {
        action = QuestConfig.normalise(action);
        amount = Math.max(0L, amount);
        final Map<String, String> normalised = new LinkedHashMap<>();
        if (metadata != null) {
            for (final Map.Entry<String, String> entry : metadata.entrySet()) {
                normalised.put(QuestConfig.normalise(entry.getKey()), QuestConfig.normalise(entry.getValue()));
            }
        }
        metadata = Map.copyOf(normalised);
    }

    public static QuestAction of(final Player player, final Island island,
                                 final String action, final long amount) {
        return of(player, island, action, amount, Map.of());
    }

    public static QuestAction of(final Player player, final Island island, final String action,
                                 final long amount, final Map<String, String> metadata) {
        return new QuestAction(player == null ? null : player.getUniqueId(), island, action, amount, metadata);
    }
}
