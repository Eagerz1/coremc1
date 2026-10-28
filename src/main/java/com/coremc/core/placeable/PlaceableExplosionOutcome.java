package com.coremc.core.placeable;

/** Pure resolution rule for explosion candidates after all event handlers finish. */
final class PlaceableExplosionOutcome {

    private PlaceableExplosionOutcome() {
    }

    static boolean shouldUnregister(final boolean cancelled, final boolean blockSurvived) {
        return !cancelled && !blockSurvived;
    }
}
