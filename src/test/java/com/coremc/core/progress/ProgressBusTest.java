package com.coremc.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Fan-out, deduplication, passive weighting and consumer isolation. */
class ProgressBusTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void everyConsumerSeesAnAcceptedEvent() {
        final ProgressBus bus = new ProgressBus(null);
        final List<ProgressEvent> first = new ArrayList<>();
        final List<ProgressEvent> second = new ArrayList<>();
        bus.register(first::add);
        bus.register(second::add);
        assertTrue(bus.post(ProgressEvent.of(PLAYER, ProgressAction.MINE_BLOCK, "stone", 3,
                ProgressSource.WORLD, "a")));
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(3, first.get(0).amount());
        assertEquals(1, bus.acceptedCount());
    }

    @Test
    void duplicatesAndEmptyEventsAreRejected() {
        final ProgressBus bus = new ProgressBus(null);
        final List<ProgressEvent> seen = new ArrayList<>();
        bus.register(seen::add);
        final ProgressEvent event = new ProgressEvent(PLAYER, ProgressAction.SLAYER_KILL, "zombie",
                1, ProgressSource.WORLD, "mob-uuid", 1_000);
        assertTrue(bus.post(event));
        assertFalse(bus.post(event));
        assertFalse(bus.post(ProgressEvent.of(PLAYER, ProgressAction.SLAYER_KILL, "zombie", 0,
                ProgressSource.WORLD, "other")));
        assertEquals(1, seen.size());
        assertEquals(2, bus.rejectedCount());
    }

    @Test
    void passiveOutputIsWeightedDownAndCapped() {
        final ProgressEvent small = ProgressEvent.of(PLAYER, ProgressAction.GENERATOR_OUTPUT,
                "coal", 500, ProgressSource.GENERATOR, "x");
        assertEquals(50, ProgressBus.weigh(small).amount());

        final ProgressEvent huge = ProgressEvent.of(PLAYER, ProgressAction.GENERATOR_OUTPUT,
                "netherite", 100_000, ProgressSource.GENERATOR, "y");
        assertEquals(ProgressBus.PASSIVE_CAP, ProgressBus.weigh(huge).amount());
    }

    @Test
    void activeActionsAreNeverWeighted() {
        final ProgressEvent active = ProgressEvent.of(PLAYER, ProgressAction.MINE_BLOCK, "stone",
                7, ProgressSource.WORLD, "z");
        assertEquals(7, ProgressBus.weigh(active).amount());
    }

    @Test
    void tinyPassiveOutputRoundsToNothingAndIsRejected() {
        final ProgressBus bus = new ProgressBus(null);
        final List<ProgressEvent> seen = new ArrayList<>();
        bus.register(seen::add);
        assertFalse(bus.post(ProgressEvent.of(PLAYER, ProgressAction.GENERATOR_OUTPUT, "coal", 5,
                ProgressSource.GENERATOR, "tiny")));
        assertTrue(seen.isEmpty());
    }

    @Test
    void oneBrokenConsumerNeverStopsTheOthers() {
        final ProgressBus bus = new ProgressBus(null);
        final List<ProgressEvent> healthy = new ArrayList<>();
        bus.register(event -> {
            throw new IllegalStateException("boom");
        });
        bus.register(healthy::add);
        assertTrue(bus.post(ProgressEvent.of(PLAYER, ProgressAction.FISH_CATCH, "cod", 1,
                ProgressSource.FISHING, "hook")));
        assertEquals(1, healthy.size());
    }

    @Test
    void nullEventsAndNullConsumersAreSafe() {
        final ProgressBus bus = new ProgressBus(null);
        bus.register(null);
        assertFalse(bus.post(null));
        assertEquals(1, bus.rejectedCount());
    }
}
