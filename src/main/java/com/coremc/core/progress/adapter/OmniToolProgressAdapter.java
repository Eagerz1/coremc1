package com.coremc.core.progress.adapter;

import java.util.UUID;

/** OmniTools (the Roles + OmniTool branch): tools, levels and enchants. */
public interface OmniToolProgressAdapter extends ProgressAdapter {

    OmniToolProgressAdapter ABSENT = new OmniToolProgressAdapter() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public int unlockedTools(final UUID player) {
            return 0;
        }

        @Override
        public int highestToolLevel(final UUID player) {
            return 0;
        }

        @Override
        public int unlockedEnchants(final UUID player) {
            return 0;
        }
    };

    /** How many role OmniTools the player has unlocked. */
    int unlockedTools(UUID player);

    /** Highest OmniTool level reached. */
    int highestToolLevel(UUID player);

    /** How many OmniTool enchants are unlocked. */
    int unlockedEnchants(UUID player);
}
