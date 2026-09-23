package com.coremc.core.island;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Parses the CoreMC char-grid schematic format. All validation happens
 * here, so a broken schematic fails loudly at load time instead of
 * pasting half an island into the void.
 *
 * Expected structure (see schematics/default.yml):
 * <pre>
 * name: default
 * origin: {x: -6, y: 0, z: -6}   # paste corner, relative to centre/base Y
 * spawn:  {x: 0.5, y: 4.0, z: 2.5}
 * palette:
 *   g: GRASS_BLOCK
 *   L: OAK_LOG
 * layers:                          # bottom -> top, rows are z, chars are x
 *   - ["   ",
 *      " g "]
 * </pre>
 */
public final class SchematicLoader {

    static final char AIR = ' ';
    private static final int MAX_PLACEMENTS = 250_000;

    private SchematicLoader() {
    }

    /** Loads and parses a schematic file from disk. */
    public static Schematic load(final Path file) {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalArgumentException(
                    "cannot read " + file.getFileName() + ": " + exception.getMessage(), exception);
        }
        return parse(yaml);
    }

    /** Parses and validates a schematic from an already-loaded YAML document. */
    public static Schematic parse(final YamlConfiguration yaml) {
        final String name = yaml.getString("name", "unnamed");
        final int originX = intOf(yaml, "origin.x");
        final int originY = intOf(yaml, "origin.y");
        final int originZ = intOf(yaml, "origin.z");
        final double spawnX = doubleOf(yaml, "spawn.x");
        final double spawnY = doubleOf(yaml, "spawn.y");
        final double spawnZ = doubleOf(yaml, "spawn.z");

        final Map<Character, Material> palette = readPalette(yaml);
        final List<List<String>> layers = readLayers(yaml);

        final List<Schematic.Placement> placements = new ArrayList<>();
        for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
            final List<String> rows = layers.get(layerIndex);
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                final String row = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < row.length(); columnIndex++) {
                    final char symbol = row.charAt(columnIndex);
                    if (symbol == AIR) {
                        continue;
                    }
                    final Material material = palette.get(symbol);
                    if (material == null) {
                        throw new IllegalArgumentException("schematic '" + name + "': layer " + layerIndex
                                + " row " + rowIndex + " uses undefined palette character '" + symbol + "'");
                    }
                    placements.add(new Schematic.Placement(
                            originX + columnIndex,
                            originY + layerIndex,
                            originZ + rowIndex,
                            material));
                }
            }
        }
        if (placements.isEmpty()) {
            throw new IllegalArgumentException("schematic '" + name + "': no blocks to paste");
        }
        if (placements.size() > MAX_PLACEMENTS) {
            throw new IllegalArgumentException("schematic '" + name + "': " + placements.size()
                    + " blocks exceeds the " + MAX_PLACEMENTS + " block limit");
        }
        return new Schematic(name, spawnX, spawnY, spawnZ, placements);
    }

    private static Map<Character, Material> readPalette(final YamlConfiguration yaml) {
        final ConfigurationSection section = yaml.getConfigurationSection("palette");
        if (section == null || section.getKeys(false).isEmpty()) {
            throw new IllegalArgumentException("schematic has no palette section");
        }
        final Map<Character, Material> palette = new HashMap<>();
        for (final String key : section.getKeys(false)) {
            if (key.length() != 1) {
                throw new IllegalArgumentException("palette key '" + key + "' must be a single character");
            }
            final char symbol = key.charAt(0);
            if (symbol == AIR) {
                throw new IllegalArgumentException("palette key ' ' (space) is reserved for air");
            }
            final String materialName = section.getString(key);
            final Material material = materialName == null ? null : Material.matchMaterial(materialName);
            if (material == null) {
                throw new IllegalArgumentException("palette entry '" + key + ": " + materialName
                        + "' is not a known material");
            }
            // NOTE: Material#isBlock needs a live server registry, so the
            // is-a-block check happens in SchematicService at load time.
            palette.put(symbol, material);
        }
        return palette;
    }

    private static List<List<String>> readLayers(final YamlConfiguration yaml) {
        final List<?> layers = yaml.getList("layers");
        if (layers == null || layers.isEmpty()) {
            throw new IllegalArgumentException("schematic has no layers");
        }
        final List<List<String>> parsed = new ArrayList<>();
        int expectedRows = -1;
        int expectedWidth = -1;
        for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
            if (!(layers.get(layerIndex) instanceof List<?> rawRows) || rawRows.isEmpty()) {
                throw new IllegalArgumentException("schematic layer " + layerIndex
                        + " must be a non-empty list of quoted row strings");
            }
            final List<String> rows = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < rawRows.size(); rowIndex++) {
                if (!(rawRows.get(rowIndex) instanceof String row)) {
                    throw new IllegalArgumentException("schematic layer " + layerIndex + " row " + rowIndex
                            + " must be a quoted string");
                }
                rows.add(row);
            }
            final int width = rows.get(0).length();
            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                if (rows.get(rowIndex).length() != width) {
                    throw new IllegalArgumentException("schematic layer " + layerIndex + " row " + rowIndex
                            + " has a different length than row 0 (all rows must be equally long)");
                }
            }
            if (expectedRows < 0) {
                expectedRows = rows.size();
                expectedWidth = width;
            } else if (rows.size() != expectedRows || width != expectedWidth) {
                throw new IllegalArgumentException("schematic layer " + layerIndex
                        + " has a different shape than layer 0 ("
                        + rows.size() + "x" + width + " vs " + expectedRows + "x" + expectedWidth + ")");
            }
            parsed.add(rows);
        }
        return parsed;
    }

    private static int intOf(final YamlConfiguration yaml, final String path) {
        if (!yaml.isInt(path)) {
            throw new IllegalArgumentException("schematic is missing integer '" + path + "'");
        }
        return yaml.getInt(path);
    }

    private static double doubleOf(final YamlConfiguration yaml, final String path) {
        if (!yaml.isDouble(path) && !yaml.isInt(path)) {
            throw new IllegalArgumentException("schematic is missing number '" + path + "'");
        }
        return yaml.getDouble(path);
    }
}
