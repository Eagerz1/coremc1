package com.coremc.core.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Permanent storage: nothing a season reset can touch may be lost. */
class YamlCollectionStoreTest {

    @TempDir
    Path folder;

    @Test
    void aProfileSurvivesASaveAndLoadIntact() throws IOException {
        final Path file = folder.resolve("collections-data.yml");
        final YamlCollectionStore store = new YamlCollectionStore(file, null);
        final UUID player = UUID.randomUUID();
        final CollectionProfile profile = new CollectionProfile();
        profile.add("cobblestone", 1_234);
        profile.discover("ancient_find", 2, 1_700_000_000_000L);
        profile.claim("cobblestone", 2);
        profile.unlock("title:stonebreaker");
        final Map<UUID, CollectionProfile> profiles = new LinkedHashMap<>();
        profiles.put(player, profile);
        store.save(profiles);

        final CollectionProfile loaded = store.load().get(player);
        assertEquals(1_234, loaded.amount("cobblestone"));
        assertEquals(2, loaded.discovery("ancient_find").count());
        assertEquals(1_700_000_000_000L, loaded.discovery("ancient_find").first());
        assertTrue(loaded.claimed("cobblestone", 2));
        assertTrue(loaded.unlocked("title:stonebreaker"));
    }

    @Test
    void emptyProfilesAreNotWrittenAndAMissingFileLoadsEmpty() throws IOException {
        final Path file = folder.resolve("collections-data.yml");
        final YamlCollectionStore store = new YamlCollectionStore(file, null);
        assertTrue(store.load().isEmpty());
        final Map<UUID, CollectionProfile> profiles = new LinkedHashMap<>();
        profiles.put(UUID.randomUUID(), new CollectionProfile());
        profiles.put(UUID.randomUUID(), null);
        store.save(profiles);
        assertTrue(store.load().isEmpty());
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("amounts"));
    }

    @Test
    void writesAreAtomicAndLeaveNoTemporaryFileBehind() throws IOException {
        final Path file = folder.resolve("nested").resolve("collections-data.yml");
        final YamlCollectionStore store = new YamlCollectionStore(file, null);
        final CollectionProfile profile = new CollectionProfile();
        profile.add("iron", 5);
        final Map<UUID, CollectionProfile> profiles = new LinkedHashMap<>();
        profiles.put(UUID.randomUUID(), profile);
        store.save(profiles);
        assertTrue(Files.isRegularFile(file));
        try (var stream = Files.list(file.getParent())) {
            assertTrue(stream.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void badUuidsAndCorruptFilesAreSkippedNotFatal() throws IOException {
        final Path file = folder.resolve("collections-data.yml");
        final UUID good = UUID.randomUUID();
        Files.writeString(file, "players:\n"
                + "  not-a-uuid:\n    amounts:\n      cobblestone: 10\n"
                + "  " + good + ":\n    amounts:\n      cobblestone: 40\n",
                StandardCharsets.UTF_8);
        final Map<UUID, CollectionProfile> loaded = new YamlCollectionStore(file, null).load();
        assertEquals(1, loaded.size());
        assertEquals(40, loaded.get(good).amount("cobblestone"));

        Files.writeString(file, "players: [this is not a map\n", StandardCharsets.UTF_8);
        assertTrue(new YamlCollectionStore(file, null).load().isEmpty());
    }

    @Test
    void overwritingReplacesTheWholeFileRatherThanMerging() throws IOException {
        final Path file = folder.resolve("collections-data.yml");
        final YamlCollectionStore store = new YamlCollectionStore(file, null);
        final UUID first = UUID.randomUUID();
        final CollectionProfile one = new CollectionProfile();
        one.add("cobblestone", 10);
        store.save(new LinkedHashMap<>(Map.of(first, one)));

        final UUID second = UUID.randomUUID();
        final CollectionProfile two = new CollectionProfile();
        two.add("iron", 10);
        store.save(new LinkedHashMap<>(Map.of(second, two)));

        final Map<UUID, CollectionProfile> loaded = store.load();
        assertEquals(1, loaded.size());
        assertTrue(loaded.containsKey(second));
    }
}
