package com.coremc.core.collections;

/** Why a milestone claim succeeded or did not. */
public enum ClaimResult {

    /** Rewards handed over. */
    CLAIMED,
    /** Rewards earned but parked safely (full inventory, or the system is elsewhere). */
    PENDING,
    /** The id does not exist. */
    UNKNOWN,
    /** The tier has not been reached yet. */
    NOT_REACHED,
    /** The tier has no hand-claimed rewards (everything was automatic). */
    NOTHING_TO_CLAIM,
    /** Already claimed — exactly-once guard. */
    ALREADY_CLAIMED;

    /** True when the player got something out of this call. */
    public boolean successful() {
        return this == CLAIMED || this == PENDING;
    }
}
