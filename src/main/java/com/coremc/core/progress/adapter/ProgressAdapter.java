package com.coremc.core.progress.adapter;

/**
 * Base contract for every external-system adapter: it can say whether
 * the real system is present on this branch. Everything that reads
 * from an adapter must check {@link #available()} first and degrade
 * honestly (GUIs show "coming soon", rewards are parked, never lost).
 */
public interface ProgressAdapter {

    /** True once the real system is wired in on this server. */
    boolean available();
}
