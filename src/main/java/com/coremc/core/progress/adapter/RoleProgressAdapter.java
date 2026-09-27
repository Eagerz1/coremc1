package com.coremc.core.progress.adapter;

import java.util.UUID;

/** Roles (the Roles + OmniTool branch): levels per role and spread across roles. */
public interface RoleProgressAdapter extends ProgressAdapter {

    RoleProgressAdapter ABSENT = new RoleProgressAdapter() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public int highestRoleLevel(final UUID player) {
            return 0;
        }

        @Override
        public String highestRoleName(final UUID player) {
            return "";
        }

        @Override
        public int rolesAtLeast(final UUID player, final int level) {
            return 0;
        }
    };

    /** Highest level the player has reached in any role. */
    int highestRoleLevel(UUID player);

    /** Display name of that role ("" when unknown). */
    String highestRoleName(UUID player);

    /** How many distinct roles are at least {@code level} (Jack of All Trades). */
    int rolesAtLeast(UUID player, int level);
}
