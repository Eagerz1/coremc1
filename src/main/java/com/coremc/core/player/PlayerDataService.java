package com.coremc.core.player;

import com.coremc.core.scheduler.TaskService;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Stable public facade for player profile lifecycle and persistence operations.
 * The coordinator owns the cache, ordered I/O lane, autosave, and shutdown flow.
 */
public final class PlayerDataService extends PlayerProfileLifecycleCoordinator {

    public PlayerDataService(final JavaPlugin plugin, final PlayerDataStore store, final TaskService tasks) {
        super(plugin, store, tasks);
    }
}
