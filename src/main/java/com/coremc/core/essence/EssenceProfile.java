package com.coremc.core.essence;

/**
 * One player's tracked essence balances and lifetime mob-kill count.
 * Mutable on the main thread only; the manager is the only writer.
 */
public final class EssenceProfile {

    long slayer;
    long mining;
    long farming;
    long kills;

    EssenceProfile() {
    }

    EssenceProfile(final long slayer, final long mining, final long farming, final long kills) {
        this.slayer = Math.max(0, slayer);
        this.mining = Math.max(0, mining);
        this.farming = Math.max(0, farming);
        this.kills = Math.max(0, kills);
    }

    long get(final EssenceType type) {
        return switch (type) {
            case SLAYER -> slayer;
            case MINING -> mining;
            case FARMING -> farming;
        };
    }

    void set(final EssenceType type, final long value) {
        switch (type) {
            case SLAYER -> this.slayer = value;
            case MINING -> this.mining = value;
            case FARMING -> this.farming = value;
        }
    }
}
