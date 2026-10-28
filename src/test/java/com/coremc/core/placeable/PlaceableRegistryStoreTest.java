package com.coremc.core.placeable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceableRegistryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsOnlyLegacyEmptyRootAndPreservesDamagedRegistries() throws IOException {
        final Path file = tempDir.resolve("placeables.yml");
        new YamlConfiguration().save(file.toFile());
        assertEquals(0L, Files.size(file), "the pre-marker writer saved a zero-byte empty registry");
        PlaceableRegistryStore store = new PlaceableRegistryStore(file.toFile(),
                Logger.getLogger(PlaceableRegistryStoreTest.class.getName()));

        store.load();

        assertEquals(0, store.size());
        final Path backup;
        try (var siblings = Files.list(tempDir)) {
            backup = siblings.filter(path -> !path.equals(file)).findFirst().orElseThrow();
        }
        assertTrue(backup.getFileName().toString().startsWith("placeables.yml.legacy-backup-"));
        assertEquals("", Files.readString(backup));
        assertEquals("", Files.readString(file), "the legacy source stays intact until the first save");
        assertTrue(store.save());
        assertTrue(Files.readString(file).contains("registry-version: 1"));

        final String damaged = "registry-version: [\n";
        Files.writeString(file, damaged);
        store = new PlaceableRegistryStore(file.toFile(),
                Logger.getLogger(PlaceableRegistryStoreTest.class.getName()));

        assertThrows(IllegalStateException.class, store::load);
        assertEquals(damaged, Files.readString(file));
        assertFalse(store.save());
        assertEquals(damaged, Files.readString(file));

        final String unsupported = "future-data: retained\n";
        Files.writeString(file, unsupported);
        store = new PlaceableRegistryStore(file.toFile(),
                Logger.getLogger(PlaceableRegistryStoreTest.class.getName()));

        assertThrows(IllegalStateException.class, store::load);
        assertEquals(unsupported, Files.readString(file));
    }

    @Test
    void restoresMultiplePlacementsInOneBatchAndReloadsThem() throws IOException {
        final Path file = tempDir.resolve("placeables.yml");
        final PlaceableRegistryStore store = new PlaceableRegistryStore(file.toFile(),
                Logger.getLogger(PlaceableRegistryStoreTest.class.getName()));
        store.load();

        final var first = new PlaceableService.Placement("world", PlaceableService.Type.GENERATOR,
                "cobble", null, 1, 64, 2);
        final var second = new PlaceableService.Placement("world", PlaceableService.Type.SPAWNER,
                "zombie", null, 3, 65, 4);
        assertTrue(store.restoreAfterCancelledBreakAll(List.of(first, second)));

        final PlaceableRegistryStore reloaded = new PlaceableRegistryStore(file.toFile(),
                Logger.getLogger(PlaceableRegistryStoreTest.class.getName()));
        reloaded.load();

        assertEquals(2, reloaded.size());
        assertEquals(first, reloaded.placements().get(PlaceableRegistryStore.keyOf("world", 1, 64, 2)));
        assertEquals(second, reloaded.placements().get(PlaceableRegistryStore.keyOf("world", 3, 65, 4)));
    }

    @Test
    void doesNotCreateLegacyBackupsForMissingOrVersionedFiles() throws IOException {
        final Path missing = tempDir.resolve("missing.yml");
        new PlaceableRegistryStore(missing.toFile(), Logger.getLogger(getClass().getName())).load();
        assertFalse(Files.exists(missing));

        final Path versioned = tempDir.resolve("versioned.yml");
        Files.writeString(versioned, "registry-version: 1\n");
        new PlaceableRegistryStore(versioned.toFile(), Logger.getLogger(getClass().getName())).load();

        try (var siblings = Files.list(tempDir)) {
            assertEquals(1, siblings.count(), "the versioned registry has no backup sibling");
        }
    }
}
