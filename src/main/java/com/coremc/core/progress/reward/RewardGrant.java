package com.coremc.core.progress.reward;

/** What happened when a reward was handed over. */
public enum RewardGrant {

    /** Delivered straight away (item in inventory, coins in the balance). */
    GRANTED,
    /** A permanent unlock was applied and remembered. */
    UNLOCKED,
    /** Parked in safe pending storage — full inventory, or the system is on another branch. */
    PENDING,
    /** Nothing happened: the reward was already granted before. */
    ALREADY,
    /** The reward could not be understood (bad config); logged, never silent. */
    FAILED;

    /** True when the player has the reward now or has it waiting safely. */
    public boolean successful() {
        return this == GRANTED || this == UNLOCKED || this == PENDING;
    }
}
