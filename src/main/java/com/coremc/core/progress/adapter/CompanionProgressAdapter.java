package com.coremc.core.progress.adapter;

import java.util.UUID;

/** Companions: discovery, rarity, evolution and maxed counts. */
public interface CompanionProgressAdapter extends ProgressAdapter {

    CompanionProgressAdapter ABSENT = new CompanionProgressAdapter() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public int discovered(final UUID player) {
            return 0;
        }

        @Override
        public int maxed(final UUID player) {
            return 0;
        }

        @Override
        public int total() {
            return 0;
        }
    };

    /** Companions the player has discovered. */
    int discovered(UUID player);

    /** Companions the player has taken to max level/evolution. */
    int maxed(UUID player);

    /** How many companions exist in total (0 when unknown). */
    int total();
}
