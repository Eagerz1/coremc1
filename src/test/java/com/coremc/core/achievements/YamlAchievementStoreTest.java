package com.coremc.core.achievements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Permanent Achievement storage. */
class YamlAchievementStoreTest {

    @TempDir
    Path folder;

    @Test
    void aProfileSurvivesASaveAndLoadIntact() throws IOException {
        final Path file = folder.resolve("achievements-data.yml");
        final YamlAchievementStore store = new YamlAchievementStore(file, null);
        final UUID player = UUID.randomUUID();
        final AchievementProfile profile = new AchievementProfile();
        profile.add("quarry_hand", 4_200);
        profile.addUnique("bestiary", "zombie");
        profile.addUnique("bestiary", "creeper");
        profile.earn("first_strike", 1_700_000_000_000L, -1);
        profile.earn("season_champion", 1_700_000_111_000L, 3);
        profile.claim("first_strike");
        profile.unlock("title:pioneer");
        store.save(new LinkedHashMap<>(Map.of(player, profile)));

        final AchievementProfile loaded = store.load().get(player);
        assertEquals(4_200, loaded.counter("quarry_hand"));
        assertEquals(Set.of("zombie", "creeper"), loaded.uniqueKeys("bestiary"));
        assertTrue(loaded.earned("first_strike"));
        assertEquals(1_700_000_000_000L, loaded.earnedAt("first_strike"));
        assertEquals(-1, loaded.earnedSeason("first_strike"));
        assertEquals(3, loaded.earnedSeason("season_champion"));
        assertTrue(loaded.claimed("first_strike"));
        assertTrue(loaded.unlocked("title:pioneer"));
    }

    @Test
    void emptyProfilesAreNotWrittenAndAMissingFileLoadsEmpty() throws IOException {
        final Path file = folder.resolve("achievements-data.yml");
        final YamlAchievementStore store = new YamlAchievementStore(file, null);
        assertTrue(store.load().isEmpty());
        final Map<UUID, AchievementProfile> profiles = new LinkedHashMap<>();
        profiles.put(UUID.randomUUID(), new AchievementProfile());
        profiles.put(UUID.randomUUID(), null);
        store.save(profiles);
        assertTrue(store.load().isEmpty());
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("earned"));
    }

    @Test
    void badUuidsAndCorruptFilesAreSkippedNotFatal() throws IOException {
        final Path file = folder.resolve("achievements-data.yml");
        final UUID good = UUID.randomUUID();
        Files.writeString(file, "players:\n"
                + "  not-a-uuid:\n    counters:\n      quarry_hand: 5\n"
                + "  " + good + ":\n    counters:\n      quarry_hand: 9\n",
                StandardCharsets.UTF_8);
        final Map<UUID, AchievementProfile> loaded = new YamlAchievementStore(file, null).load();
        assertEquals(1, loaded.size());
        assertEquals(9, loaded.get(good).counter("quarry_hand"));

        Files.writeString(file, "players: [broken: yaml\n", StandardCharsets.UTF_8);
        assertTrue(new YamlAchievementStore(file, null).load().isEmpty());
    }

    @Test
    void writesAreAtomicAndCreateMissingFolders() throws IOException {
        final Path file = folder.resolve("nested").resolve("achievements-data.yml");
        final YamlAchievementStore store = new YamlAchievementStore(file, null);
        final AchievementProfile profile = new AchievementProfile();
        profile.earn("first_strike", 1L, -1);
        store.save(new LinkedHashMap<>(Map.of(UUID.randomUUID(), profile)));
        assertTrue(Files.isRegularFile(file));
        try (var stream = Files.list(file.getParent())) {
            assertTrue(stream.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }
}
