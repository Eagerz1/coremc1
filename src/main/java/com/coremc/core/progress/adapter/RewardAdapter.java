package com.coremc.core.progress.adapter;

import java.util.UUID;

/**
 * Currencies and keys that live on other branches: Credits, Sky Tokens
 * and crate keys.
 *
 * <p>Every method returns whether the grant actually happened. A
 * {@code false} never means "lost": the reward services park the grant
 * in safe pending storage so the real system can hand it over later.</p>
 */
public interface RewardAdapter extends ProgressAdapter {

    RewardAdapter ABSENT = new RewardAdapter() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean grantCredits(final UUID player, final long amount) {
            return false;
        }

        @Override
        public boolean grantSkyTokens(final UUID player, final long amount) {
            return false;
        }

        @Override
        public boolean grantKey(final UUID player, final String keyId, final int amount) {
            return false;
        }
    };

    /** Small Credits rewards (never large economy advantages). */
    boolean grantCredits(UUID player, long amount);

    /** Sky Tokens — the island progression currency. */
    boolean grantSkyTokens(UUID player, long amount);

    /** Crate keys by stable id. */
    boolean grantKey(UUID player, String keyId, int amount);
}
