package com.coremc.core.progression;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/** Atomic persistence for hourly event schedule recovery. */
public final class YamlServerEventStore {

    public record State(String activeEvent, long activeStartedAt, long activeEndsAt,
                        long nextStartAt, String lastEvent) {
    }

    private final Path file;
    private final Logger logger;

    public YamlServerEventStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public State load() throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        if (!Files.isRegularFile(file)) {
            return new State("", 0L, 0L, 0L, "");
        }
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            logger.warning("Corrupt event state " + file + " — starting fresh: " + exception.getMessage());
            return new State("", 0L, 0L, 0L, "");
        }
        return new State(yaml.getString("active.event", ""),
                yaml.getLong("active.started-at", 0L), yaml.getLong("active.ends-at", 0L),
                yaml.getLong("next-start-at", 0L), yaml.getString("last-event", ""));
    }

    public void save(final State state) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        if (state.activeEvent() != null && !state.activeEvent().isBlank() && state.activeEndsAt() > 0L) {
            yaml.set("active.event", state.activeEvent());
            yaml.set("active.started-at", state.activeStartedAt());
            yaml.set("active.ends-at", state.activeEndsAt());
        }
        yaml.set("next-start-at", state.nextStartAt());
        yaml.set("last-event", state.lastEvent());
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
