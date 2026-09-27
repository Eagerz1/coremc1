package com.coremc.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Island progression data survives restarts without touching island files. */
class YamlIslandProgressionStoreTest {

    @TempDir
    Path directory;

    @Test
    void profileRoundTrips() throws IOException {
        final Path file = directory.resolve("island-progression.yml");
        final YamlIslandProgressionStore store = new YamlIslandProgressionStore(file,
                Logger.getLogger("test"));
        final UUID islandId = UUID.randomUUID();
        final IslandProgressionProfile profile = new IslandProgressionProfile(islandId);
        profile.setXp(12345);
        profile.setSkyTokens(250);
        profile.setMasteryLevel("farming", "crop-mutation", 1);
        profile.addOwnedModule("verdant");
        profile.setActiveModules(java.util.Set.of("verdant"));
        profile.setLastModuleSwapAt(9000L);
        profile.setFortuneFocus("mining", 8000L);
        profile.setMomentum(55.5D);
        profile.setActiveUntil("slayer-frenzy", 12_000L);
        profile.setCooldownUntil("rich-veins", 15_000L);
        profile.addDiscovery("ancient-seed", 2);
        profile.sourceWindow("farming", 1000L).addXp(42.5);

        final Map<UUID, IslandProgressionProfile> profiles = new LinkedHashMap<>();
        profiles.put(islandId, profile);
        store.saveAll(profiles);

        final Map<UUID, IslandProgressionProfile> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        final IslandProgressionProfile restored = loaded.get(islandId);
        assertEquals(12345, restored.xp());
        assertEquals(250, restored.skyTokens());
        assertEquals(1, restored.masteryLevel("farming", "crop-mutation"));
        assertTrue(restored.ownedModules().contains("verdant"));
        assertTrue(restored.activeModules().contains("verdant"));
        assertEquals(9000L, restored.lastModuleSwapAt());
        assertEquals("mining", restored.fortuneFocus());
        assertEquals(8000L, restored.fortuneSelectedAt());
        assertEquals(55.5, restored.momentum(), 0.001);
        assertEquals(12_000L, restored.activeUntil("slayer-frenzy"));
        assertEquals(15_000L, restored.cooldownUntil("rich-veins"));
        assertEquals(2, restored.discoveries().get("ancient-seed"));
        assertEquals(42.5, restored.sourceWindows().get("farming").xp(), 0.001);
    }

    @Test
    void missingFileLoadsEmpty() throws IOException {
        final YamlIslandProgressionStore store = new YamlIslandProgressionStore(
                directory.resolve("missing.yml"), Logger.getLogger("test"));
        assertTrue(store.loadAll().isEmpty());
    }
}
