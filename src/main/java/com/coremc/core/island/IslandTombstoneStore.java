package com.coremc.core.island;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Durable reservation store for deleted island IDs and retired grid cells. */
public final class IslandTombstoneStore {

    private final Path file;
    private final Set<String> retiredCells = ConcurrentHashMap.newKeySet();
    private final Set<UUID> deletedIslandIds = ConcurrentHashMap.newKeySet();

    public IslandTombstoneStore(final Path file) {
        this.file = file;
    }

    public void load() throws IOException {
        if (!Files.exists(file)) return;
        for (final String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            if (line.startsWith("island:")) {
                try {
                    deletedIslandIds.add(UUID.fromString(line.substring("island:".length())));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid island tombstone in " + file + ": " + line, exception);
                }
            } else {
                retiredCells.add(line);
            }
        }
    }

    /** Adds both reservations atomically, rolling back memory if the disk commit fails. */
    public synchronized void retire(final String cellKey, final UUID islandId) throws IOException {
        final boolean newlyRetired = retiredCells.add(cellKey);
        final boolean newlyDeleted = deletedIslandIds.add(islandId);
        try {
            persist();
        } catch (IOException exception) {
            if (newlyRetired) retiredCells.remove(cellKey);
            if (newlyDeleted) deletedIslandIds.remove(islandId);
            throw exception;
        }
    }

    public boolean isRetired(final String cellKey, final UUID islandId) {
        return deletedIslandIds.contains(islandId) || retiredCells.contains(cellKey);
    }

    public Set<String> reservedCells() {
        return Set.copyOf(retiredCells);
    }

    public int retiredCellCount() {
        return retiredCells.size();
    }

    private void persist() throws IOException {
        final Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);

        final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        final List<String> sorted = new ArrayList<>(retiredCells);
        for (final UUID deletedIslandId : deletedIslandIds) {
            sorted.add("island:" + deletedIslandId);
        }
        Collections.sort(sorted);
        final String contents = sorted.isEmpty() ? "" : String.join(System.lineSeparator(), sorted)
                + System.lineSeparator();
        Files.writeString(temporary, contents, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
