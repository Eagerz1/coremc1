package com.coremc.core.quest;

import com.coremc.core.island.Island;
import com.coremc.core.progression.ServerEventService;
import com.coremc.core.season.SeasonJourneyService;
import com.coremc.core.season.SeasonXpSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Central publication API for quest progress. Gameplay systems emit meaningful
 * actions here; QuestService owns all template matching and reward logic.
 */
public final class QuestProgressService {

    private static final int EVENT_MEANINGFUL_ACTIONS = 5;

    private QuestService quests;
    private ServerEventService events;
    private SeasonJourneyService seasonJourney;
    private final Map<String, Integer> eventContributionCounts = new LinkedHashMap<>();

    public void attach(final QuestService quests) {
        this.quests = quests;
    }

    public void attachEvents(final ServerEventService events) {
        this.events = events;
    }

    public void attachSeasonJourney(final SeasonJourneyService seasonJourney) {
        this.seasonJourney = seasonJourney;
    }

    public void publish(final Player player, final Island island, final String action, final long amount) {
        publish(player, island, action, amount, Map.of());
    }

    public void publish(final Player player, final Island island, final String action,
                        final long amount, final Map<String, String> metadata) {
        if (amount <= 0L) {
            return;
        }
        if (quests != null) {
            final QuestAction event = QuestAction.of(player, island, action, amount, metadata);
            quests.recordAction(event);
        }
        if (events != null && events.currentEvent() != null && isActiveGameplay(action)) {
            if (quests != null) {
                quests.recordAction(QuestAction.of(player, island, "hourly-event-participation", 1L,
                        Map.of("event", events.currentEvent().id())));
            }
            awardSeasonEventParticipation(player, action);
        }
    }

    public void publishIsland(final Island island, final String action, final long amount,
                              final Map<String, String> metadata) {
        if (quests == null || amount <= 0L) {
            return;
        }
        quests.recordAction(new QuestAction(null, island, action, amount, metadata));
    }

    private void awardSeasonEventParticipation(final Player player, final String action) {
        if (seasonJourney == null || player == null || !isSeasonEventGameplay(action)) {
            return;
        }
        final UUID playerId = player.getUniqueId();
        final String eventKey = events.activeInstanceKey();
        if (eventKey.isBlank()) {
            return;
        }
        final String countKey = playerId + ":" + eventKey;
        final int count = eventContributionCounts.getOrDefault(countKey, 0) + 1;
        eventContributionCounts.put(countKey, count);
        if (count >= EVENT_MEANINGFUL_ACTIONS) {
            seasonJourney.addConfiguredXpOnce(playerId, SeasonXpSource.EVENT,
                    "event-participation:" + eventKey + ":" + playerId);
        }
    }

    private boolean isActiveGameplay(final String action) {
        final String id = QuestConfig.normalise(action);
        return switch (id) {
            case "crop-harvested", "fish-caught", "valid-mob-killed", "mining-block-mined",
                 "mining-cube-block-mined", "spawner-placed", "spawner-upgraded",
                 "island-xp-gained", "island-buff-triggered", "discovery-found" -> true;
            default -> false;
        };
    }

    private boolean isSeasonEventGameplay(final String action) {
        final String id = QuestConfig.normalise(action);
        // No passive generator/spawner outputs or tiny online/idle ticks: only active play.
        return switch (id) {
            case "crop-harvested", "fish-caught", "valid-mob-killed", "mining-block-mined",
                 "mining-cube-block-mined", "island-xp-gained", "island-buff-triggered" -> true;
            default -> false;
        };
    }
}
