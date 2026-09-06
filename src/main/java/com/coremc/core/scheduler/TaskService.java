package com.coremc.core.scheduler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Central task registry for CoreMC.
 *
 * Every repeating or long-running task started by the plugin goes through
 * this service so that {@link #cancelAll()} on shutdown leaves nothing
 * behind. This prevents "task leaked after plugin disable" problems.
 */
public final class TaskService {

    private final JavaPlugin plugin;
    private final Set<BukkitTask> trackedTasks = ConcurrentHashMap.newKeySet();

    public TaskService(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Runs a repeating task on the main thread and tracks it. */
    public BukkitTask runTimer(final Runnable runnable, final long delayTicks, final long periodTicks) {
        return track(Bukkit.getScheduler().runTaskTimer(plugin, wrap(runnable), delayTicks, periodTicks));
    }

    /** Runs a repeating task asynchronously and tracks it. */
    public BukkitTask runTimerAsync(final Runnable runnable, final long delayTicks, final long periodTicks) {
        return track(Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, wrap(runnable), delayTicks, periodTicks));
    }

    /** Runs a one-off task asynchronously and tracks it. */
    public BukkitTask runAsync(final Runnable runnable) {
        return track(Bukkit.getScheduler().runTaskAsynchronously(plugin, wrap(runnable)));
    }

    /** Runs a one-off task on the main thread and tracks it. */
    public BukkitTask run(final Runnable runnable) {
        return track(Bukkit.getScheduler().runTask(plugin, wrap(runnable)));
    }

    /** Cancels a single tracked task (idempotent). */
    public void cancel(final BukkitTask task) {
        if (task != null) {
            task.cancel();
            trackedTasks.remove(task);
        }
    }

    private BukkitTask track(final BukkitTask task) {
        trackedTasks.add(task);
        return task;
    }

    /** Number of tasks currently registered (used by /coremc info). */
    public int trackedTaskCount() {
        return trackedTasks.size();
    }

    /**
     * Cancels every tracked task. Called on plugin disable before services
     * shut down, so no task can touch dead services afterwards.
     */
    public void cancelAll() {
        for (final BukkitTask task : trackedTasks) {
            try {
                task.cancel();
            } catch (final IllegalStateException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to cancel task " + task.getTaskId(), exception);
            }
        }
        trackedTasks.clear();
    }

    /** Wraps a runnable so a failure inside a task is logged instead of silent. */
    private Runnable wrap(final Runnable runnable) {
        return () -> {
            try {
                runnable.run();
            } catch (final RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Unhandled exception in tracked task", exception);
            }
        };
    }
}
