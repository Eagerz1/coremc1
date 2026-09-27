package com.coremc.core.progress.adapter;

import java.util.UUID;

/**
 * Quests and hourly events. Both systems <em>write</em> through the
 * progression bus; this adapter is the read side used by the profile
 * summary and by GUI copy.
 */
public interface QuestEventAdapter extends ProgressAdapter {

    QuestEventAdapter ABSENT = new QuestEventAdapter() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public int questsCompleted(final UUID player) {
            return 0;
        }

        @Override
        public int eventsParticipated(final UUID player) {
            return 0;
        }
    };

    /** Lifetime completed quests. */
    int questsCompleted(UUID player);

    /** Lifetime meaningful event participations (being online never counts). */
    int eventsParticipated(UUID player);
}
