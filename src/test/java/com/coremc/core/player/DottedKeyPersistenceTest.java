package com.coremc.core.player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for Bukkit path splitting: dotted identifiers
 * (enchant ids like "miner.treasure-miner", stat keys like
 * "crate-pity:sky") must survive a YAML save/load round trip verbatim.
 * Bukkit's YamlConfiguration explodes dotted keys into nested sections,
 * so the profile store serialises with raw SnakeYAML.
 */
class DottedKeyPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void dottedEnchantIdsRoundTrip() throws IOException {
        final YamlPlayerDataStore store = new YamlPlayerDataStore(tempDir);
        final UUID uuid = UUID.randomUUID();
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Enchanter", 1_000L);
        profile.recordLogin("Enchanter", 2_000L);
        profile.setEnchantLevel("miner.treasure-miner", 3);
        profile.setEnchantLevel("universal.lucky", 1);
        profile.setStat("crate-pity:sky", 2L);
        store.save(profile);

        final Optional<PlayerProfile> loaded = store.load(uuid);
        assertTrue(loaded.isPresent());
        assertEquals(3, loaded.get().enchantLevel("miner.treasure-miner"),
                "dotted enchant id must not be split into nested sections");
        assertEquals(1, loaded.get().enchantLevel("universal.lucky"));
        assertEquals(2L, loaded.get().statOf("crate-pity:sky"), "colon stat key survives");
        // The mangled nested representation must NOT exist.
        assertEquals(0, loaded.get().enchantLevel("miner"));
    }

    @Test
    void onDiskYamlKeepsDottedKeysLiteral() throws IOException {
        final YamlPlayerDataStore store = new YamlPlayerDataStore(tempDir);
        final UUID uuid = UUID.randomUUID();
        final PlayerProfile profile = PlayerProfile.createNew(uuid, "Dot", 1L);
        profile.recordLogin("Dot", 2L);
        profile.setEnchantLevel("miner.treasure-miner", 1);
        store.save(profile);

        final String onDisk = Files.readString(tempDir.resolve(uuid + ".yml"));
        assertTrue(onDisk.contains("miner.treasure-miner"),
                "on-disk YAML must keep the dotted id literal:\n" + onDisk);
    }

    @Test
    void usernameIndexRoundTrips() throws IOException {
        final YamlPlayerDataStore store = new YamlPlayerDataStore(tempDir);
        final UUID uuid = UUID.randomUUID();
        store.saveNameIndex(Map.of("dot", uuid));
        final var loaded = store.loadNameIndex();
        assertEquals(uuid, loaded.get("dot"));
    }
}
