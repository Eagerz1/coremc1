package com.coremc.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ServerEventConfigTest {

    private static ServerEventConfig parse(final String yaml) {
        final ServerEventConfig config = new ServerEventConfig(null);
        final YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.loadFromString(yaml);
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(loaded);
        return config;
    }

    private static ServerEventConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/events.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void shippedEventsContainRequiredPoolAndTiming() throws IOException {
        final ServerEventConfig config = bundled();
        assertEquals(60L * 60L * 1000L, config.intervalMillis());
        assertEquals(15L * 60L * 1000L, config.durationMillis());
        assertEquals(9, config.events().size());
        for (final String id : ServerEventConfig.REQUIRED_EVENTS) {
            assertNotNull(config.event(id), id);
        }
    }

    @Test
    void slayerEventDoesNotDeclareMoneyOrVanillaDropMultiplier() throws IOException {
        final ServerEventConfig.EventDef slayer = bundled().event("slayer-frenzy");
        assertEquals(2.0, slayer.effect("kill-progression-multiplier", 1), 0.001);
        assertEquals(2.0, slayer.effect("omnitool-output-multiplier", 1), 0.001);
        assertEquals(1.0, slayer.effect("money-multiplier", 1), 0.001);
        assertEquals(1.0, slayer.effect("vanilla-drop-multiplier", 1), 0.001);
    }

    @Test
    void durationMustBeShorterThanInterval() throws IOException {
        final String yaml = Files.readString(Path.of("src/main/resources/events.yml"), StandardCharsets.UTF_8)
                .replace("duration-minutes: 15", "duration-minutes: 60");
        assertThrows(IllegalArgumentException.class, () -> parse(yaml));
    }

    @Test
    void missingRequiredEventIsRejected() throws IOException {
        final String yaml = Files.readString(Path.of("src/main/resources/events.yml"), StandardCharsets.UTF_8)
                .replaceFirst("(?s)  farming-festival:.*", "");
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parse(yaml));
        assertTrue(error.getMessage().contains("farming-festival"), error.getMessage());
    }
}
