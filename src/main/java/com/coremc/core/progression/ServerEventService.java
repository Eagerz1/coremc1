package com.coremc.core.progression;

import com.coremc.core.config.MessageService;
import com.coremc.core.util.ColorUtil;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Hourly server-wide event scheduler/runtime. */
public final class ServerEventService {

    private final JavaPlugin plugin;
    private final ServerEventConfig config;
    private final YamlServerEventStore store;
    private final MessageService messages;
    private final Logger logger;
    private final LongSupplier clock;
    private final Random random;
    private ServerEventConfig.EventDef active;
    private long activeStartedAt;
    private long activeEndsAt;
    private long nextStartAt;
    private String lastEvent = "";
    private boolean announcedFive;
    private boolean announcedOne;
    private BossBar bossBar;

    public ServerEventService(final JavaPlugin plugin, final ServerEventConfig config,
                              final YamlServerEventStore store, final MessageService messages,
                              final Logger logger) {
        this(plugin, config, store, messages, logger, System::currentTimeMillis, new Random());
    }

    ServerEventService(final JavaPlugin plugin, final ServerEventConfig config,
                       final YamlServerEventStore store, final MessageService messages,
                       final Logger logger, final LongSupplier clock, final Random random) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.messages = messages;
        this.logger = logger;
        this.clock = clock;
        this.random = random;
    }

    public void load() {
        try {
            final YamlServerEventStore.State state = store.load();
            this.lastEvent = state.lastEvent() == null ? "" : state.lastEvent();
            this.nextStartAt = state.nextStartAt() <= 0L ? now() + config.intervalMillis() : state.nextStartAt();
            if (state.activeEndsAt() > now()) {
                final ServerEventConfig.EventDef def = config.event(state.activeEvent());
                if (def != null) {
                    this.active = def;
                    this.activeStartedAt = state.activeStartedAt();
                    this.activeEndsAt = state.activeEndsAt();
                    createBossBar();
                }
            }
        } catch (final IOException exception) {
            logger.warning("Event schedule starts fresh — " + exception.getMessage());
            this.nextStartAt = now() + config.intervalMillis();
        }
        if (plugin != null && config.enabled()) {
            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(), 20L, 20L);
        }
    }

    public void tick() {
        final long now = now();
        if (active != null && now >= activeEndsAt) {
            stop(false);
        }
        if (active != null) {
            maybeAnnounceRemaining();
            updateBossBar();
            // If the automatic schedule came due during a manual/active event,
            // advance it without starting a duplicate or overlapping event.
            while (nextStartAt <= now) {
                nextStartAt += config.intervalMillis();
            }
            persistQuietly();
            return;
        }
        if (config.enabled() && now >= nextStartAt) {
            final ServerEventConfig.EventDef next = chooseNext();
            if (next != null) {
                start(next.id(), false);
            }
        }
    }

    public boolean start(final String eventId, final boolean manual) {
        final ServerEventConfig.EventDef def = config.event(eventId);
        if (def == null || active != null) {
            return false;
        }
        this.active = def;
        this.activeStartedAt = now();
        this.activeEndsAt = activeStartedAt + config.durationMillis();
        this.announcedFive = false;
        this.announcedOne = false;
        this.lastEvent = def.id();
        if (!manual) {
            this.nextStartAt = activeStartedAt + config.intervalMillis();
        }
        createBossBar();
        announce("event.start", def);
        persistQuietly();
        return true;
    }

    public boolean stop(final boolean manual) {
        if (active == null) {
            return false;
        }
        final ServerEventConfig.EventDef stopped = active;
        active = null;
        activeStartedAt = 0L;
        activeEndsAt = 0L;
        announcedFive = false;
        announcedOne = false;
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        announce("event.end", stopped);
        persistQuietly();
        return true;
    }

    public ServerEventConfig.EventDef currentEvent() {
        return active;
    }

    public String activeInstanceKey() {
        return active == null ? "" : active.id() + ":" + activeStartedAt;
    }

    public long remainingMillis() {
        return active == null ? 0L : Math.max(0L, activeEndsAt - now());
    }

    public long nextStartAt() {
        return nextStartAt;
    }

    public boolean isEffectActive(final String effect) {
        return active != null && active.effects().containsKey(ServerEventConfig.normalise(effect));
    }

    public boolean isEventActive(final String eventId) {
        return active != null && active.id().equals(ServerEventConfig.normalise(eventId));
    }

    public double multiplier(final String key, final double fallback) {
        return active == null ? fallback : active.effect(key, fallback);
    }

    public void shutdown() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        persistQuietly();
    }

    private ServerEventConfig.EventDef chooseNext() {
        final List<ServerEventConfig.EventDef> events = config.events();
        if (events.isEmpty()) {
            return null;
        }
        if (events.size() == 1 || !config.avoidImmediateRepeats()) {
            return events.get(random.nextInt(events.size()));
        }
        ServerEventConfig.EventDef candidate;
        int guard = 0;
        do {
            candidate = events.get(random.nextInt(events.size()));
            guard++;
        } while (candidate.id().equals(lastEvent) && guard < 20);
        return candidate.id().equals(lastEvent) ? events.stream()
                .filter(event -> !event.id().equals(lastEvent)).findFirst().orElse(candidate) : candidate;
    }

    private void maybeAnnounceRemaining() {
        final long remaining = remainingMillis();
        if (!announcedFive && remaining <= 5L * 60L * 1000L && remaining > 60L * 1000L) {
            announcedFive = true;
            announce("event.remaining", active, "5 minutes");
        }
        if (!announcedOne && remaining <= 60L * 1000L) {
            announcedOne = true;
            announce("event.remaining", active, "1 minute");
        }
    }

    private void createBossBar() {
        if (plugin == null || active == null) {
            return;
        }
        if (bossBar != null) {
            bossBar.removeAll();
        }
        bossBar = Bukkit.createBossBar(bossTitle(), active.color(), BarStyle.SOLID);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(player);
        }
    }

    private void updateBossBar() {
        if (bossBar == null || active == null) {
            return;
        }
        bossBar.setTitle(bossTitle());
        bossBar.setProgress(Math.max(0.0D, Math.min(1.0D,
                remainingMillis() / (double) Math.max(1L, config.durationMillis()))));
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (!bossBar.getPlayers().contains(player)) {
                bossBar.addPlayer(player);
            }
        }
    }

    private String bossTitle() {
        return ColorUtil.colorize("&c&l" + (active == null ? "Event" : smallCaps(active.name()))
                + " &7• &f" + formatDuration(remainingMillis()));
    }

    private void announce(final String key, final ServerEventConfig.EventDef def) {
        announce(key, def, "");
    }

    private void announce(final String key, final ServerEventConfig.EventDef def, final String time) {
        if (messages == null || def == null) {
            return;
        }
        final Map<String, String> placeholders = Map.of(
                "event", def.name(),
                "time", time,
                "remaining", formatDuration(remainingMillis()));
        for (final Player player : Bukkit.getOnlinePlayers()) {
            messages.sendPrefixed(player, key, placeholders);
            if ("event.start".equals(key)) {
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.1f);
            }
        }
    }

    private void persistQuietly() {
        try {
            store.save(new YamlServerEventStore.State(active == null ? "" : active.id(),
                    activeStartedAt, activeEndsAt, nextStartAt, lastEvent));
        } catch (final IOException exception) {
            logger.warning("Could not save event state: " + exception.getMessage());
        }
    }

    private long now() {
        return clock.getAsLong();
    }

    private String formatDuration(final long millis) {
        final long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private String smallCaps(final String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace('a', 'ᴀ').replace('b', 'ʙ').replace('c', 'ᴄ').replace('d', 'ᴅ')
                .replace('e', 'ᴇ').replace('f', 'ғ').replace('g', 'ɢ').replace('h', 'ʜ')
                .replace('i', 'ɪ').replace('j', 'ᴊ').replace('k', 'ᴋ').replace('l', 'ʟ')
                .replace('m', 'ᴍ').replace('n', 'ɴ').replace('o', 'ᴏ').replace('p', 'ᴘ')
                .replace('q', 'ǫ').replace('r', 'ʀ').replace('s', 's').replace('t', 'ᴛ')
                .replace('u', 'ᴜ').replace('v', 'ᴠ').replace('w', 'ᴡ').replace('x', 'x')
                .replace('y', 'ʏ').replace('z', 'ᴢ');
    }
}
