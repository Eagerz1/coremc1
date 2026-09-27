package com.coremc.core.quest;

import com.coremc.core.island.Island;
import com.coremc.core.progression.ServerEventService;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Central publication API for quest progress. Gameplay systems emit meaningful
 * actions here; QuestService owns all template matching and reward logic.
 */
public final class QuestProgressService {

    private QuestService quests;
    private ServerEventService events;

    public void attach(final QuestService quests) {
        this.quests = quests;
    }

    public void attachEvents(final ServerEventService events) {
        this.events = events;
    }

    public void publish(final Player player, final Island island, final String action, final long amount) {
        publish(player, island, action, amount, Map.of());
    }

    public void publish(final Player player, final Island island, final String action,
                        final long amount, final Map<String, String> metadata) {
        if (quests == null || amount <= 0L) {
            return;
        }
        final QuestAction event = QuestAction.of(player, island, action, amount, metadata);
        quests.recordAction(event);
        if (events != null && events.currentEvent() != null && isActiveGameplay(action)) {
            quests.recordAction(QuestAction.of(player, island, "hourly-event-participation", 1L,
                    Map.of("event", events.currentEvent().id())));
        }
    }

    public void publishIsland(final Island island, final String action, final long amount,
                              final Map<String, String> metadata) {
        if (quests == null || amount <= 0L) {
            return;
        }
        quests.recordAction(new QuestAction(null, island, action, amount, metadata));
    }

    private boolean isActiveGameplay(final String action) {
        final String id = QuestConfig.normalise(action);
        return switch (id) {
            case "crop-harvested", "fish-caught", "valid-mob-killed", "mining-block-mined",
                 "mining-cube-block-mined", "spawner-placed", "spawner-upgraded", "resource-generated",
                 "island-xp-gained", "island-buff-triggered", "discovery-found" -> true;
            default -> false;
        };
    }
}
