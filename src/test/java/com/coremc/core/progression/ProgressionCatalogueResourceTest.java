package com.coremc.core.progression;

import com.coremc.core.util.RawYaml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the shipped generator and spawner catalogue to the public baseline. */
class ProgressionCatalogueResourceTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config() throws IOException {
        try (InputStream stream = ProgressionCatalogueResourceTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "config.yml must be bundled");
            return RawYaml.parseMap(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, Object> pluginDescription() throws IOException {
        try (InputStream stream = ProgressionCatalogueResourceTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream, "plugin.yml must be bundled");
            return RawYaml.parseMap(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void progressionMenusAreAvailableToRegularPlayers() throws IOException {
        final Map<String, Object> permissions =
                (Map<String, Object>) pluginDescription().get("permissions");
        assertNotNull(permissions);
        for (final String permission : List.of("coremc.command.companions", "coremc.command.quests")) {
            final Map<String, Object> definition = (Map<String, Object>) permissions.get(permission);
            assertNotNull(definition, permission + " must be declared explicitly");
            assertEquals(Boolean.TRUE, definition.get("default"),
                    permission + " must be available to non-op players");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shipsTwentyFourUsableGenerators() throws IOException {
        final Map<String, Object> generators = (Map<String, Object>) config().get("generators");
        assertNotNull(generators);
        assertEquals(24, generators.size(), "generator baseline changed");
        for (final Map.Entry<String, Object> row : generators.entrySet()) {
            final Map<String, Object> def = (Map<String, Object>) row.getValue();
            final Material block = Material.matchMaterial(String.valueOf(def.get("block")));
            final Material product = Material.matchMaterial(String.valueOf(def.get("product")));
            assertNotNull(block, row.getKey() + " has an invalid block");
            // isBlock() consults Paper's live RegistryAccess and cannot run in plain unit tests.
            assertTrue(block != Material.AIR, row.getKey() + " generator shell cannot be air");
            assertNotNull(product, row.getKey() + " has an invalid product");
            assertTrue(((Number) def.get("price")).longValue() > 0L, row.getKey() + " needs a price");
            assertTrue(((Number) def.get("cooldown-seconds")).longValue() >= 3L,
                    row.getKey() + " cooldown is too short");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shipsThirtyDistinctRegularSpawnerLanes() throws IOException {
        final Map<String, Object> spawners = (Map<String, Object>) config().get("spawners");
        assertNotNull(spawners);
        int lanes = 0;
        final Set<String> entities = new HashSet<>();
        for (final Map.Entry<String, Object> row : spawners.entrySet()) {
            if (!(row.getValue() instanceof Map<?, ?> raw) || !raw.containsKey("entity")) {
                continue;
            }
            final Map<String, Object> def = (Map<String, Object>) raw;
            lanes++;
            final String entity = String.valueOf(def.get("entity"));
            assertNotNull(EntityType.valueOf(entity), row.getKey() + " has an invalid entity");
            assertTrue(entities.add(entity), "entity progress key reused: " + entity);
            assertNotNull(Material.matchMaterial(String.valueOf(def.get("icon"))),
                    row.getKey() + " has an invalid icon");
            assertTrue(!def.containsKey("tiers"), row.getKey() + " must not ship variants");
            assertTrue(((Number) def.get("required-kills")).longValue() > 0L,
                    row.getKey() + " needs a kill gate");
            assertTrue(((Number) def.get("price")).longValue() > 0L,
                    row.getKey() + " needs a token price");
            assertEquals(1, ((Number) def.get("spawn-count")).intValue(),
                    row.getKey() + " must be a regular single-spawn spawner");
        }
        assertEquals(30, lanes, "spawner lane baseline changed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shipsSixEarnableCompanionsCoveringEveryActivity() throws IOException {
        final Map<String, Object> companions = (Map<String, Object>) config().get("companions");
        assertNotNull(companions);
        assertEquals(6, companions.size());
        final Set<String> categories = new HashSet<>();
        for (final Map.Entry<String, Object> row : companions.entrySet()) {
            final Map<String, Object> def = (Map<String, Object>) row.getValue();
            categories.add(String.valueOf(def.get("category")));
            assertNotNull(Material.matchMaterial(String.valueOf(def.get("icon"))),
                    row.getKey() + " has an invalid icon");
            assertTrue(((Number) def.get("price-sky-tokens")).longValue() > 0L);
            assertEquals(20, ((Number) def.get("max-level")).intValue());
        }
        assertEquals(Set.of("MINING", "LOGGING", "FISHING", "FARMING", "SLAYING"), categories);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shipsTenDailyMissionsWithEarnedRewards() throws IOException {
        final Map<String, Object> quests = (Map<String, Object>) config().get("daily-quests");
        assertNotNull(quests);
        assertEquals(10, quests.size());
        final Set<String> metrics = new HashSet<>();
        for (final Map.Entry<String, Object> row : quests.entrySet()) {
            final Map<String, Object> def = (Map<String, Object>) row.getValue();
            metrics.add(String.valueOf(def.get("metric")));
            assertTrue(((Number) def.get("target")).longValue() > 0L);
            assertTrue(((Number) def.get("reward-credits")).longValue() > 0L);
            assertTrue(((Number) def.get("reward-sky-tokens")).longValue() > 0L);
        }
        assertEquals(Set.of("MINE_BLOCK", "CHOP_LOG", "HARVEST_CROP", "CATCH_FISH", "KILL_MOB"), metrics);
    }
}
