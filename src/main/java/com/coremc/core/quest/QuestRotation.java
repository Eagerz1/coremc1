package com.coremc.core.quest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Deterministic daily assignment and progress-state math. */
public final class QuestRotation {

    public record Progress(long amount, boolean done, boolean claimed) {
    }

    private QuestRotation() {
    }

    public static List<String> select(
            final List<String> ids, final UUID player, final String dayKey, final int count) {
        final List<String> shuffled = new ArrayList<>(ids);
        final long seed = player.getMostSignificantBits() ^ player.getLeastSignificantBits() ^ dayKey.hashCode();
        Collections.shuffle(shuffled, new Random(seed));
        return List.copyOf(shuffled.subList(0, Math.min(Math.max(0, count), shuffled.size())));
    }

    public static Progress advance(final Progress current, final long amount, final long target) {
        if (current.claimed() || amount <= 0L) {
            return current;
        }
        final long safeTarget = Math.max(1L, target);
        final long next = Math.min(safeTarget, current.amount() > Long.MAX_VALUE - amount
                ? safeTarget : current.amount() + amount);
        return new Progress(next, next >= safeTarget, false);
    }
}
