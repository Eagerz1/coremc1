package com.coremc.core.progress.adapter;

import java.util.UUID;

/**
 * The Season Journey (a separate active branch). Seasonal achievements
 * read the level and the season id through here and record the season
 * id with the completion, so history survives the next reset.
 */
public interface SeasonJourneyAdapter extends ProgressAdapter {

    SeasonJourneyAdapter ABSENT = new SeasonJourneyAdapter() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public int journeyLevel(final UUID player) {
            return 0;
        }

        @Override
        public int currentSeason() {
            return 0;
        }

        @Override
        public boolean grantJourneyXp(final UUID player, final long amount) {
            return false;
        }
    };

    /** The player's Season Journey level this season. */
    int journeyLevel(UUID player);

    /** The current season id (0 when unknown). */
    int currentSeason();

    /** Grants Journey XP; false when the system is not present (reward is parked). */
    boolean grantJourneyXp(UUID player, long amount);
}
