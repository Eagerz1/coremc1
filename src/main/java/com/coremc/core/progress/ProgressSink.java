package com.coremc.core.progress;

/**
 * The one-line dependency other CoreMC systems take on progression.
 *
 * <p>Generators, spawners and any future system post facts into a sink
 * instead of knowing about Collections or Achievements. A sink may be
 * absent (the systems check for null) and posting is always safe and
 * cheap — the bus does the deduplication and the fan-out.</p>
 */
@FunctionalInterface
public interface ProgressSink {

    /**
     * Records an authoritative progression event.
     *
     * @return true when it was accepted (not a duplicate, not filtered)
     */
    boolean post(ProgressEvent event);
}
