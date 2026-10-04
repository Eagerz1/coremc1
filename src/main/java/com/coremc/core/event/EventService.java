package com.coremc.core.event;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.scheduler.TaskService;
import com.coremc.core.util.ColorUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Runs the scheduled Core Hour: one hour of doubled island XP, slaying money,
 * and Omni-Tool XP every four hours. Schedule anchor is persisted so restarts
 * do not reset the countdown or extend an active event.
 */
public final class EventService implements Listener {

    private final CoreMCPlugin plugin;
    private final Path statePath;
    private BossBar bossBar;
    private long anchorMillis;
    private long intervalMillis;
    private long durationMillis;
    private boolean wasActive;
    private EventSchedule.Window current;

    public EventService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.statePath = plugin.getDataFolder().toPath().resolve("events.yml");
    }

    public void load() {
        final long now = System.currentTimeMillis();
        final long intervalHours = Math.max(1L,
                Math.min(168L, plugin.getConfig().getLong("events.interval-hours", 4L)));
        intervalMillis = Math.multiplyExact(intervalHours, 60L * 60L * 1000L);
        final long durationMinutes = Math.max(1L,
                Math.min(10_080L, plugin.getConfig().getLong("events.duration-minutes", 60L)));
        durationMillis = Math.min(intervalMillis, Math.multiplyExact(durationMinutes, 60L * 1000L));

        final YamlConfiguration state = YamlConfiguration.loadConfiguration(statePath.toFile());
        anchorMillis = state.getLong("schedule-anchor-millis", 0L);
        if (anchorMillis <= 0L) {
            anchorMillis = now + intervalMillis;
            saveAnchor();
        }
        current = EventSchedule.at(now, anchorMillis, intervalMillis, durationMillis);
        wasActive = current.active();
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID);
            bossBar.setVisible(false);
        }
        syncBar(now);
    }

    public void start(final TaskService tasks) {
        tasks.runTimer(this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
        }
    }

    private void tick() {
        final long now = System.currentTimeMillis();
        final EventSchedule.Window updated = EventSchedule.at(now, anchorMillis, intervalMillis, durationMillis);
        if (updated.active() && !wasActive) {
            Bukkit.broadcastMessage(ColorUtil.colorize(
                    "&b&lCORE HOUR &7has started! &a2x Island XP, Slaying Money, and Omni-Tool XP for 1 hour."));
        } else if (!updated.active() && wasActive) {
            Bukkit.broadcastMessage(ColorUtil.colorize("&7Core Hour has ended. The next event is in "
                    + formatDuration(updated.remainingMillis(now)) + "&7."));
        }
        current = updated;
        wasActive = updated.active();
        syncBar(now);
    }

    private void syncBar(final long now) {
        if (bossBar == null || current == null) return;
        bossBar.setVisible(current.active());
        if (!current.active()) {
            bossBar.removeAll();
            return;
        }
        final int done = current.completedSegments(now);
        final String segments = "&a" + "■".repeat(done) + "&7" + "■".repeat(4 - done);
        final String title = "&b&lCORE HOUR &8[" + segments + "&8] &7" + formatDuration(current.remainingMillis(now))
                + " &8| &a2x Island XP &8• &e2x Slaying Money &8• &d2x Omni XP";
        bossBar.setTitle(ColorUtil.colorize(title));
        bossBar.setProgress(current.bossBarProgress(now));
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (!bossBar.getPlayers().contains(player)) bossBar.addPlayer(player);
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        if (wasActive && bossBar != null) bossBar.addPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        if (bossBar != null) bossBar.removePlayer(event.getPlayer());
    }

    public boolean active() {
        return current != null && current.active();
    }

    public double islandXpMultiplier() {
        return active() ? multiplier("island-xp") : 1.0;
    }

    public double slayingMoneyMultiplier() {
        return active() ? multiplier("slaying-money") : 1.0;
    }

    public double omniToolXpMultiplier() {
        return active() ? multiplier("omnitool-xp") : 1.0;
    }

    public long remainingMillis() {
        return current == null ? 0L : current.remainingMillis(System.currentTimeMillis());
    }

    public long nextStartMillis() {
        return current == null ? anchorMillis : current.nextStartMillis();
    }

    private double multiplier(final String key) {
        return Math.max(1.0, Math.min(10.0,
                plugin.getConfig().getDouble("events.multipliers." + key, 2.0)));
    }

    private void saveAnchor() {
        final YamlConfiguration state = new YamlConfiguration();
        state.set("schedule-anchor-millis", anchorMillis);
        try {
            Files.createDirectories(statePath.getParent());
            state.save(statePath.toFile());
        } catch (final IOException exception) {
            plugin.getLogger().warning("Could not save event schedule: " + exception.getMessage());
        }
    }

    public static String formatDuration(final long millis) {
        final long seconds = Math.max(0L, millis / 1000L);
        final long hours = seconds / 3600L;
        final long minutes = (seconds % 3600L) / 60L;
        final long remainder = seconds % 60L;
        return hours > 0L
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder)
                : String.format(Locale.ROOT, "%d:%02d", minutes, remainder);
    }
}
