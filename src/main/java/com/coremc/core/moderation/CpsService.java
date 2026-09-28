package com.coremc.core.moderation;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/** CPS measurement from vanilla arm-swing events. No packets and one shared timer only while active. */
public final class CpsService implements Listener {

    private final com.coremc.core.CoreMCPlugin plugin;
    private final ModerationService moderation;
    private final Set<Measurement> active = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<Measurement>> byTarget = new ConcurrentHashMap<>();
    private BukkitTask task;

    public CpsService(final com.coremc.core.CoreMCPlugin plugin, final ModerationService moderation) {
        this.plugin = plugin;
        this.moderation = moderation;
    }

    public void start(final Player staff, final Player target) {
        final int seconds = moderation.config().cpsWindowSeconds();
        final Measurement measurement = new Measurement(
                staff.getUniqueId(), staff.getName(), target.getUniqueId(), target.getName(),
                System.currentTimeMillis(), seconds, new int[seconds]);
        active.add(measurement);
        byTarget.computeIfAbsent(target.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(measurement);
        moderation.prefixed(staff, "&aMeasuring arm-swing CPS for &f" + target.getName() + "&a for &f" + seconds
                + "s&a. This is not proof of cheating by itself.");
        ensureTask();
    }

    private void ensureTask() {
        if (task != null && !task.isCancelled()) {
            return;
        }
        task = plugin.tasks().runTimer(this::tick, 20L, 20L);
    }

    private void tick() {
        final long now = System.currentTimeMillis();
        for (final Measurement measurement : new ArrayList<>(active)) {
            final Player staff = Bukkit.getPlayer(measurement.staffUuid());
            final Player target = Bukkit.getPlayer(measurement.targetUuid());
            if (staff == null || target == null) {
                cancel(measurement, staff, "player disconnected");
                continue;
            }
            final int elapsed = (int) ((now - measurement.startMillis()) / 1000L);
            if (elapsed >= measurement.windowSeconds()) {
                report(staff, measurement, true);
                remove(measurement);
                continue;
            }
            if (elapsed > measurement.lastReportedSecond()) {
                measurement.lastReportedSecond(elapsed);
                report(staff, measurement, false);
            }
        }
        if (active.isEmpty() && task != null) {
            plugin.tasks().cancel(task);
            task = null;
        }
    }

    private void report(final Player staff, final Measurement measurement, final boolean finished) {
        final int elapsed = Math.max(1, Math.min(measurement.windowSeconds(),
                (int) ((System.currentTimeMillis() - measurement.startMillis()) / 1000L)));
        final int currentBucket = finished ? measurement.windowSeconds() - 1 : Math.max(0, elapsed - 1);
        final int current = measurement.bucket(currentBucket);
        final int total = measurement.total();
        final int peak = measurement.peak();
        final double average = total / (double) elapsed;
        moderation.prefixed(staff, (finished ? "&bCPS result" : "&7CPS update") + " &8» &f" + measurement.targetName()
                + " &7current &f" + current + "&7, average &f" + String.format(java.util.Locale.ROOT, "%.2f", average)
                + "&7, peak &f" + peak + "&7. &8(Arm swings only; not proof.)");
    }

    private void cancel(final Measurement measurement, final Player staff, final String reason) {
        remove(measurement);
        if (staff != null) {
            moderation.prefixed(staff, "&cCPS check on &f" + measurement.targetName() + "&c cancelled: " + reason + ".");
        }
    }

    private void remove(final Measurement measurement) {
        active.remove(measurement);
        final Set<Measurement> set = byTarget.get(measurement.targetUuid());
        if (set != null) {
            set.remove(measurement);
            if (set.isEmpty()) {
                byTarget.remove(measurement.targetUuid());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmSwing(final PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        final Set<Measurement> measurements = byTarget.get(event.getPlayer().getUniqueId());
        if (measurements == null || measurements.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final Measurement measurement : measurements) {
            final int second = (int) ((now - measurement.startMillis()) / 1000L);
            if (second >= 0 && second < measurement.windowSeconds()) {
                measurement.increment(second);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        for (final Measurement measurement : new ArrayList<>(active)) {
            if (measurement.staffUuid().equals(event.getPlayer().getUniqueId())) {
                remove(measurement);
            } else if (measurement.targetUuid().equals(event.getPlayer().getUniqueId())) {
                final Player staff = Bukkit.getPlayer(measurement.staffUuid());
                cancel(measurement, staff, "target disconnected");
            }
        }
    }

    private static final class Measurement {
        private final UUID staffUuid;
        private final String staffName;
        private final UUID targetUuid;
        private final String targetName;
        private final long startMillis;
        private final int windowSeconds;
        private final int[] buckets;
        private int lastReportedSecond;

        private Measurement(
                final UUID staffUuid, final String staffName, final UUID targetUuid, final String targetName,
                final long startMillis, final int windowSeconds, final int[] buckets) {
            this.staffUuid = staffUuid;
            this.staffName = staffName;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.startMillis = startMillis;
            this.windowSeconds = windowSeconds;
            this.buckets = buckets;
            this.lastReportedSecond = 0;
        }

        UUID staffUuid() { return staffUuid; }
        String staffName() { return staffName; }
        UUID targetUuid() { return targetUuid; }
        String targetName() { return targetName; }
        long startMillis() { return startMillis; }
        int windowSeconds() { return windowSeconds; }
        int lastReportedSecond() { return lastReportedSecond; }
        void lastReportedSecond(final int second) { this.lastReportedSecond = second; }

        synchronized void increment(final int second) {
            buckets[second]++;
        }

        synchronized int bucket(final int second) {
            return buckets[Math.max(0, Math.min(second, buckets.length - 1))];
        }

        synchronized int total() {
            int total = 0;
            for (final int bucket : buckets) {
                total += bucket;
            }
            return total;
        }

        synchronized int peak() {
            int peak = 0;
            for (final int bucket : buckets) {
                peak = Math.max(peak, bucket);
            }
            return peak;
        }
    }
}
