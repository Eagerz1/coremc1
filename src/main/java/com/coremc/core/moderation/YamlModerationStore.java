package com.coremc.core.moderation;

import com.coremc.core.util.RawYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** YAML-backed moderation storage. One atomic state file plus append-only audit log. */
public final class YamlModerationStore {

    private final Path directory;
    private final Path stateFile;
    private final Path auditFile;
    private final Logger logger;

    public YamlModerationStore(final Path directory, final Logger logger) {
        this.directory = directory;
        this.stateFile = directory.resolve("moderation.yml");
        this.auditFile = directory.resolve("audit.log");
        this.logger = logger;
    }

    public ModerationSnapshot load() {
        if (!Files.isRegularFile(stateFile)) {
            return ModerationSnapshot.empty();
        }
        try {
            final Map<String, Object> root = RawYaml.loadMap(stateFile.toFile());
            return ModerationSnapshot.fromMap(root);
        } catch (final RuntimeException exception) {
            logger.log(Level.SEVERE, "Failed to load moderation state from " + stateFile, exception);
            return ModerationSnapshot.empty();
        }
    }

    public void save(final ModerationSnapshot snapshot) throws IOException {
        RawYaml.writeAtomic(stateFile, RawYaml.dump(snapshot.toMap()));
    }

    public void appendAudit(final String line) {
        try {
            Files.createDirectories(directory);
            Files.writeString(auditFile, Instant.now() + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (final IOException exception) {
            logger.log(Level.WARNING, "Failed to append moderation audit line", exception);
        }
    }
}
