package com.coremc.core.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Append-only store transaction record
 * ({@code plugins/CoreMC/store-transactions.log}): every purchase,
 * crate opening, lootbox opening and admin grant leaves one line, so
 * disputes are always answerable. Failures to write never fail the
 * transaction itself — they log loudly instead.
 */
public final class TransactionLog {

    private final Path file;
    private final Logger logger;

    public TransactionLog(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Records one transaction line. */
    public void record(final String txnId, final UUID player, final String action,
                       final String detail) {
        final String line = Instant.now() + " txn=" + txnId + " player=" + player
                + " action=" + action + (detail == null || detail.isEmpty() ? "" : " " + detail)
                + System.lineSeparator();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException exception) {
            logger.severe("Could not append to " + file.getFileName() + ": "
                    + exception.getMessage() + " — line was: " + line.trim());
        }
    }
}
