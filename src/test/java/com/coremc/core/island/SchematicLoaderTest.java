package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Parsing and validation of the char-grid schematic format. */
class SchematicLoaderTest {

    private static final String VALID = """
            name: test
            origin: {x: -1, y: 0, z: -1}
            spawn: {x: 0.5, y: 2.0, z: 0.5}
            palette:
              g: GRASS_BLOCK
              b: BEDROCK
              C: CHEST
            layers:
              - ["   ",
                 " bC",
                 " g "]
            """;

    private Schematic parse(final String document) {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(document);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        return SchematicLoader.parse(yaml);
    }

    @Test
    void parsesOriginSpawnAndPlacements() {
        final Schematic schematic = parse(VALID);
        assertEquals("test", schematic.name());
        assertEquals(0.5, schematic.spawnX());
        assertEquals(2.0, schematic.spawnY());
        assertEquals(0.5, schematic.spawnZ());
        assertEquals(3, schematic.blockCount());

        // layer 0, row 1: 'b' at col 1 -> (0, 0, 0), 'C' at col 2 -> (1, 0, 0)
        // layer 0, row 2: 'g' at col 1 -> (0, 0, 1)
        assertTrue(contains(schematic, 0, 0, 0, Material.BEDROCK));
        assertTrue(contains(schematic, 1, 0, 0, Material.CHEST));
        assertTrue(contains(schematic, 0, 0, 1, Material.GRASS_BLOCK));
    }

    @Test
    void spaceMeansAirAndIsSkipped() {
        final Schematic schematic = parse(VALID);
        assertEquals(3, schematic.blockCount(), "spaces must not produce placements");
    }

    @Test
    void unknownMaterialIsRejected() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        name: bad
                        origin: {x: 0, y: 0, z: 0}
                        spawn: {x: 0.5, y: 1.0, z: 0.5}
                        palette:
                          g: NOT_A_REAL_BLOCK
                        layers:
                          - ["g"]
                        """));
        assertTrue(exception.getMessage().contains("NOT_A_REAL_BLOCK"));
    }

    @Test
    void nonBlockMaterialsParseHereAndAreRejectedAtLoadTime() {
        // Material#isBlock needs a live server registry, so the loader only
        // checks the material is KNOWN; the is-a-block check is deferred to
        // SchematicService.load (documented there).
        final Schematic schematic = parse("""
                name: sword
                origin: {x: 0, y: 0, z: 0}
                spawn: {x: 0.5, y: 1.0, z: 0.5}
                palette:
                  s: DIAMOND_SWORD
                layers:
                  - ["s"]
                """);
        assertEquals(1, schematic.blockCount());
        assertTrue(contains(schematic, 0, 0, 0, Material.DIAMOND_SWORD));
    }

    @Test
    void undefinedPaletteCharacterIsRejected() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        name: bad
                        origin: {x: 0, y: 0, z: 0}
                        spawn: {x: 0.5, y: 1.0, z: 0.5}
                        palette:
                          g: GRASS_BLOCK
                        layers:
                          - ["g",
                             "q"]
                        """));
        assertTrue(exception.getMessage().contains("'q'"));
    }

    @Test
    void raggedRowsAreRejected() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        name: bad
                        origin: {x: 0, y: 0, z: 0}
                        spawn: {x: 0.5, y: 1.0, z: 0.5}
                        palette:
                          g: GRASS_BLOCK
                        layers:
                          - ["gg",
                             "g"]
                        """));
        assertTrue(exception.getMessage().contains("length"));
    }

    @Test
    void mismatchedLayerShapesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        name: bad
                        origin: {x: 0, y: 0, z: 0}
                        spawn: {x: 0.5, y: 1.0, z: 0.5}
                        palette:
                          g: GRASS_BLOCK
                          d: DIRT
                        layers:
                          - ["gg",
                             "gg"]
                          - ["ddd"]
                        """));
    }

    @Test
    void emptySchematicIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        name: empty
                        origin: {x: 0, y: 0, z: 0}
                        spawn: {x: 0.5, y: 1.0, z: 0.5}
                        palette:
                          g: GRASS_BLOCK
                        layers:
                          - [" "]
                        """));
    }

    @Test
    void missingSectionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        name: bad
                        origin: {x: 0, y: 0, z: 0}
                        layers:
                          - ["g"]
                        """));
    }

    @Test
    void theShippedDefaultSchematicIsValid() {
        final Path file = Path.of("src/main/resources/schematics/default.yml");
        assertTrue(java.nio.file.Files.exists(file),
                "default.yml must ship in the jar resources");
        final Schematic schematic = SchematicLoader.load(file);

        assertEquals("default", schematic.name());
        assertNotNull(schematic);
        assertTrue(schematic.blockCount() > 150, "default island should be a real island");
        assertTrue(contains(schematic, 0, 0, 0, Material.BEDROCK), "bedrock core under the centre");
        assertTrue(schematic.placements().stream()
                        .anyMatch(placement -> placement.material() == Material.CHEST),
                "default island must contain a starter chest");
        assertTrue(contains(schematic, 0, 3, 2, Material.GRASS_BLOCK), "grass under the spawn point");
        assertEquals(4.0, schematic.spawnY(), "spawn must stand on the grass surface (y+3)");
    }

    private boolean contains(final Schematic schematic, final int x, final int y, final int z,
                             final Material material) {
        return schematic.placements().contains(new Schematic.Placement(x, y, z, material));
    }
}
