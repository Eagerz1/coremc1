package com.coremc.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerEventServiceTest {

    @TempDir
    Path directory;

    private static ServerEventConfig config() throws IOException {
        final ServerEventConfig config = new ServerEventConfig(null);
        final YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.loadFromString(Files.readString(Path.of("src/main/resources/events.yml"),
                    StandardCharsets.UTF_8));
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(loaded);
        return config;
    }

    private ServerEventService service(final ServerEventConfig config, final AtomicLong now) {
        return new ServerEventService(null, config,
                new YamlServerEventStore(directory.resolve("events-state.yml"), Logger.getLogger("test")),
                null, Logger.getLogger("test"), now::get, new Random(1L));
    }

    @Test
    void manualStartStopDoesNotOverlapOrDuplicate() throws IOException {
        final AtomicLong now = new AtomicLong(1_000L);
        final ServerEventService service = service(config(), now);
        service.load();
        assertTrue(service.start("slayer-frenzy", true));
        assertFalse(service.start("mining-frenzy", true), "no overlap");
        assertEquals("slayer-frenzy", service.currentEvent().id());
        assertEquals(2.0, service.multiplier("kill-progression-multiplier", 1.0), 0.001);
        assertTrue(service.stop(true));
        assertNull(service.currentEvent());
    }

    @Test
    void automaticScheduleStartsAndAvoidsImmediateRepeat() throws IOException {
        final AtomicLong now = new AtomicLong(10_000L);
        final ServerEventConfig config = config();
        final ServerEventService service = service(config, now);
        service.load();
        now.set(service.nextStartAt());
        service.tick();
        final String first = service.currentEvent().id();
        now.addAndGet(config.durationMillis());
        service.tick();
        assertNull(service.currentEvent());
        now.set(service.nextStartAt());
        service.tick();
        assertNotEquals(first, service.currentEvent().id(), "rotation avoids immediate repeats");
    }

    @Test
    void restartRecoversActiveEventAndCleansAfterExpiry() throws IOException {
        final AtomicLong now = new AtomicLong(5_000L);
        final ServerEventConfig config = config();
        ServerEventService service = service(config, now);
        service.load();
        assertTrue(service.start("fishing-rush", true));

        service = service(config, now);
        service.load();
        assertEquals("fishing-rush", service.currentEvent().id());
        now.addAndGet(config.durationMillis() + 1L);
        service.tick();
        assertNull(service.currentEvent());
    }

    @Test
    void manualStartDoesNotMoveAutomaticNextStart() throws IOException {
        final AtomicLong now = new AtomicLong(7_000L);
        final ServerEventService service = service(config(), now);
        service.load();
        final long next = service.nextStartAt();
        assertTrue(service.start("core-surge", true));
        assertEquals(next, service.nextStartAt());
    }
}
