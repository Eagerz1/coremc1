package com.coremc.core.progression;

import java.util.LinkedHashSet;
import java.util.Set;

/** Pure equip/swap rules used by IslandCoreBuffService and unit tests. */
public final class CoreBuffEquipRules {

    public enum Status {
        EQUIPPED, UNEQUIPPED, LOCKED, NO_SLOT, COOLDOWN
    }

    public record Result(Status status, Set<String> active, long lastSwapAt) {
        public boolean changed() {
            return status == Status.EQUIPPED || status == Status.UNEQUIPPED;
        }
    }

    private CoreBuffEquipRules() {
    }

    public static Result toggle(final Set<String> unlocked, final Set<String> active, final String id,
                                final int slots, final long lastSwapAt, final long now,
                                final long cooldownMillis) {
        final Set<String> next = new LinkedHashSet<>(active);
        if (!unlocked.contains(id)) {
            return new Result(Status.LOCKED, next, lastSwapAt);
        }
        if (lastSwapAt > 0L && cooldownMillis > 0L && lastSwapAt + cooldownMillis > now) {
            return new Result(Status.COOLDOWN, next, lastSwapAt);
        }
        if (next.contains(id)) {
            next.remove(id);
            return new Result(Status.UNEQUIPPED, next, now);
        }
        if (next.size() >= Math.max(0, slots)) {
            return new Result(Status.NO_SLOT, next, lastSwapAt);
        }
        next.add(id);
        return new Result(Status.EQUIPPED, next, now);
    }
}
