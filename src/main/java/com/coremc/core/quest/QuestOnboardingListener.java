package com.coremc.core.quest;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Lightweight first-session guidance: one welcome, persisted by QuestService. */
public final class QuestOnboardingListener implements Listener {

    private final QuestService quests;

    public QuestOnboardingListener(final QuestService quests) {
        this.quests = quests;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        if (quests != null && quests.config().enabled()) {
            quests.onJoin(event.getPlayer());
        }
    }
}
