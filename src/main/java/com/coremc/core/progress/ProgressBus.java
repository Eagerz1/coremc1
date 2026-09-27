package com.coremc.core.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The authoritative progression bus: every counted player action in
 * CoreMC passes through here exactly once.
 *
 * <p>Producers (the world bridge, generators, spawners, adapters for
 * the systems that live on other branches) call {@link #post}; the bus
 * deduplicates the action, applies the passive-output weighting and
 * hands the event to the consumers — Collections and Achievements
 * today, anything else later. Consumers never see the same real action
 * twice, so mining one iron block can never be recorded by three
 * different listeners.</p>
 *
 * <p>Main-thread only, like the rest of CoreMC. A misbehaving consumer
 * is logged and skipped instead of breaking the chain.</p>
 */
public final class ProgressBus implements ProgressSink {

    /** Share of a passive action that actually counts (AFK output must not dominate). */
    public static final double PASSIVE_WEIGHT = 0.1;
    /** Hard cap on a single passive event's counted amount. */
    public static final long PASSIVE_CAP = 64L;

    private final List<Consumer<ProgressEvent>> consumers = new ArrayList<>();
    private final ProgressDeduplicator deduplicator;
    private final Logger logger;
    private long accepted;
    private long rejected;

    public ProgressBus(final Logger logger) {
        this(logger, new ProgressDeduplicator());
    }

    public ProgressBus(final Logger logger, final ProgressDeduplicator deduplicator) {
        this.logger = logger;
        this.deduplicator = deduplicator;
    }

    /** Registers a consumer (Collections, Achievements, …). */
    public void register(final Consumer<ProgressEvent> consumer) {
        if (consumer != null) {
            consumers.add(consumer);
        }
    }

    @Override
    public boolean post(final ProgressEvent event) {
        if (event == null || event.empty()) {
            rejected++;
            return false;
        }
        if (!deduplicator.accept(event, event.timestamp())) {
            rejected++;
            return false;
        }
        final ProgressEvent weighted = weigh(event);
        if (weighted.empty()) {
            rejected++;
            return false;
        }
        accepted++;
        for (final Consumer<ProgressEvent> consumer : consumers) {
            try {
                consumer.accept(weighted);
            } catch (final RuntimeException failure) {
                if (logger != null) {
                    logger.warning("Progression consumer failed for " + weighted.action().id()
                            + ": " + failure.getMessage());
                }
            }
        }
        return true;
    }

    /**
     * Passive output (generator payouts) counts at a fraction of its
     * raw amount and is capped, so an AFK island can never complete a
     * Collection on its own.
     */
    public static ProgressEvent weigh(final ProgressEvent event) {
        if (!event.action().passive()) {
            return event;
        }
        final long weighted = Math.min(PASSIVE_CAP, (long) Math.floor(event.amount() * PASSIVE_WEIGHT));
        return event.withAmount(weighted);
    }

    /** How many events were accepted (diagnostics). */
    public long acceptedCount() {
        return accepted;
    }

    /** How many events were rejected as duplicates/empty (diagnostics). */
    public long rejectedCount() {
        return rejected;
    }

    /** The deduplicator, for reloads and tests. */
    public ProgressDeduplicator deduplicator() {
        return deduplicator;
    }
}
