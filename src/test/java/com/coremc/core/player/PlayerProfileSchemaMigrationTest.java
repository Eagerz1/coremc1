package com.coremc.core.player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two feature branches both used schema v7 for different additive fields.
 * The final schema is v8 and must load either v7 shape without dropping data.
 */
class PlayerProfileSchemaMigrationTest {

    private static final UUID UUID_A = UUID.nameUUIDFromBytes("schema-migration".getBytes());

    @Test
    void v6FieldsRemainLosslessWhenMigratedToV8() {
        final Map<String, Object> v6 = new LinkedHashMap<>();
        v6.put("schema-version", 6);
        v6.put("username", "Legacy");
        v6.put("role", "farmer");
        v6.put("money", 1200L);
        v6.put("owned-tags", List.of("grinder"));
        v6.put("chat-color", "sunset");
        v6.put("chat-bold", true);
        v6.put("enchant-levels", Map.of("farmer.crop-mastery", 3));

        final PlayerProfile loaded = PlayerProfile.fromMap(UUID_A, v6);
        final Map<String, Object> migrated = loaded.toMap();

        assertEquals(8, migrated.get("schema-version"));
        assertEquals(1200L, migrated.get("money"));
        assertEquals(List.of("grinder"), migrated.get("owned-tags"));
        assertEquals("sunset", migrated.get("chat-color"));
        assertEquals(true, migrated.get("chat-bold"));
        assertEquals(Map.of("farmer.crop-mastery", 3), migrated.get("enchant-levels"));
        assertTrue(loaded.ownedSkins().isEmpty());
        assertTrue(loaded.ownedChatStyles().isEmpty());
    }

    @Test
    void animatedV7FieldsSurviveWithoutChatStyleData() {
        final Map<String, Object> v7 = new LinkedHashMap<>();
        v7.put("schema-version", 7);
        v7.put("username", "Skinned");
        v7.put("owned-skins", List.of("emberforge_miner"));
        v7.put("equipped-tool-skins", Map.of("miner", "emberforge_miner"));
        v7.put("equipped-hat", "ember_crown");

        final PlayerProfile loaded = PlayerProfile.fromMap(UUID_A, v7);

        assertTrue(loaded.ownsSkin("emberforge_miner"));
        assertEquals("emberforge_miner", loaded.equippedToolSkin("miner").orElseThrow());
        assertEquals("ember_crown", loaded.equippedHat());
        assertTrue(loaded.ownedChatStyles().isEmpty());
        assertEquals(8, loaded.toMap().get("schema-version"));
    }

    @Test
    void chatStyleV7FieldsSurviveWithoutSkinData() {
        final Map<String, Object> v7 = new LinkedHashMap<>();
        v7.put("schema-version", 7);
        v7.put("username", "Coloured");
        v7.put("owned-chat-styles", List.of("sunset", "bold"));
        v7.put("chat-color", "sunset");
        v7.put("chat-bold", true);

        final PlayerProfile loaded = PlayerProfile.fromMap(UUID_A, v7);

        assertTrue(loaded.hasChatStyle("sunset"));
        assertTrue(loaded.hasChatStyle("bold"));
        assertEquals(List.of("sunset", "bold"), loaded.toMap().get("owned-chat-styles"));
        assertTrue(loaded.ownedSkins().isEmpty());
        assertEquals(8, loaded.toMap().get("schema-version"));
    }
}
