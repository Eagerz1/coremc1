package com.coremc.core.island;

import com.coremc.core.util.RawYaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable write-ahead record for one in-flight island token purchase. */
final class IslandPurchaseJournal {
    record Pending(String id, UUID playerId, UUID islandId, String kind,
                   String key, int tier, long balanceAfter) {}

    private final Path file;

    IslandPurchaseJournal(final Path file) {
        this.file = file;
    }

    synchronized void write(final Pending pending) throws IOException {
        final Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("id", pending.id());
        transaction.put("player", pending.playerId().toString());
        transaction.put("island", pending.islandId().toString());
        transaction.put("kind", pending.kind());
        transaction.put("key", pending.key());
        transaction.put("tier", pending.tier());
        transaction.put("balance-after", pending.balanceAfter());
        RawYaml.writeAtomic(file, RawYaml.dump(Map.of("version", 1, "transaction", transaction)));
    }

    synchronized Optional<Pending> load() throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        final Map<String, Object> root;
        try {
            root = RawYaml.loadMap(file.toFile());
        } catch (final RawYaml.YamlLoadException exception) {
            throw new IOException("Corrupt island purchase journal " + file, exception);
        }
        if (!(root.get("transaction") instanceof Map<?, ?> raw)) {
            throw new IOException("Island purchase journal has no transaction record: " + file);
        }
        try {
            final Map<String, Object> transaction = RawYaml.toStringKeyedMap(raw);
            if (((Number) root.get("version")).intValue() != 1) {
                throw new IllegalArgumentException("unsupported journal version");
            }
            final Pending pending = new Pending(
                    String.valueOf(transaction.get("id")),
                    UUID.fromString(String.valueOf(transaction.get("player"))),
                    UUID.fromString(String.valueOf(transaction.get("island"))),
                    String.valueOf(transaction.get("kind")),
                    String.valueOf(transaction.get("key")),
                    ((Number) transaction.get("tier")).intValue(),
                    ((Number) transaction.get("balance-after")).longValue());
            if (pending.id().isBlank() || !(pending.kind().equals("upgrade") || pending.kind().equals("buff"))
                    || pending.key().isBlank() || pending.tier() < 1 || pending.balanceAfter() < 0) {
                throw new IllegalArgumentException("invalid transaction values");
            }
            return Optional.of(pending);
        } catch (final RuntimeException exception) {
            throw new IOException("Invalid island purchase journal " + file, exception);
        }
    }

    synchronized void clear() throws IOException {
        Files.deleteIfExists(file);
    }
}