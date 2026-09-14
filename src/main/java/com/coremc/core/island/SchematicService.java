package com.coremc.core.island;

import com.coremc.core.config.CoreConfig;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads schematics (disk first, jar as fallback) and pastes them into
 * the island world. The bundled default schematic is extracted to
 * {@code plugins/CoreMC/schematics/} on first run so admins can edit it.
 */
public final class SchematicService {

    private static final String NAME_PATTERN = "[a-zA-Z0-9_-]+";
    private static final String RESOURCE_DIR = "schematics/";

    private final JavaPlugin plugin;
    private final Path directory;

    public SchematicService(final JavaPlugin plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "schematics").toPath();
    }

    /** Copies the bundled default schematic to disk on first run. */
    public void extractDefault() {
        try {
            Files.createDirectories(directory);
            final Path target = directory.resolve("default.yml");
            if (!Files.exists(target) && plugin.getResource(RESOURCE_DIR + "default.yml") != null) {
                plugin.saveResource(RESOURCE_DIR + "default.yml", false);
            }
        } catch (final IOException exception) {
            plugin.getLogger().warning("Could not create schematics directory: " + exception.getMessage());
        }
    }

    /**
     * Loads the named schematic: {@code plugins/CoreMC/schematics/<name>.yml}
     * if present, otherwise the copy bundled in the jar.
     *
     * @throws IllegalArgumentException if the name is invalid, nothing is
     *         found, or the file fails validation
     */
    public Schematic load(final String name) {
        if (name == null || !name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("invalid schematic name '" + name + "'");
        }
        final Path file = directory.resolve(name + ".yml");
        final Schematic schematic;
        if (Files.exists(file)) {
            schematic = SchematicLoader.load(file);
        } else {
            schematic = loadFromJar(name);
        }
        validateMaterials(schematic);
        return schematic;
    }

    private Schematic loadFromJar(final String name) {
        try (InputStream stream = plugin.getResource(RESOURCE_DIR + name + ".yml")) {
            if (stream != null) {
                final YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                return SchematicLoader.parse(yaml);
            }
        } catch (final Exception exception) {
            throw new IllegalArgumentException(
                    "schematic '" + name + "' is invalid: " + exception.getMessage(), exception);
        }
        throw new IllegalArgumentException("schematic '" + name + "' not found (looked in "
                + directory + " and inside the jar)");
    }

    /**
     * Material#isBlock requires a live server registry, so this final
     * palette check runs here (server present) rather than in the pure
     * loader — before anything is pasted into the world.
     */
    private void validateMaterials(final Schematic schematic) {
        for (final Schematic.Placement placement : schematic.placements()) {
            if (!placement.material().isBlock()) {
                throw new IllegalArgumentException("schematic '" + schematic.name()
                        + "': material " + placement.material() + " is not a block type — check the palette");
            }
        }
    }

    /**
     * Pastes the schematic at the island position. The claimed chunks are
     * pre-generated as void before pasting, so a claim never intersects
     * terrain generated later.
     *
     * @return the number of blocks placed
     */
    public int paste(final World world, final Schematic schematic, final int centerX, final int baseY,
                     final int centerZ, final int claimSize, final List<CoreConfig.ChestItem> chestItems) {
        preGenerate(world, centerX, centerZ, claimSize);

        final List<Block> chests = new ArrayList<>();
        int placed = 0;
        for (final Schematic.Placement placement : schematic.placements()) {
            final Block block = world.getBlockAt(
                    centerX + placement.x(),
                    baseY + placement.y(),
                    centerZ + placement.z());
            block.setType(placement.material(), false);
            placed++;
            if (placement.material() == Material.CHEST) {
                chests.add(block);
            }
        }
        fillChests(chests, chestItems);
        return placed;
    }

    private void preGenerate(final World world, final int centerX, final int centerZ, final int claimSize) {
        final int half = Math.max(8, claimSize / 2);
        final int minChunkX = Math.floorDiv(centerX - half, 16);
        final int maxChunkX = Math.floorDiv(centerX + half, 16);
        final int minChunkZ = Math.floorDiv(centerZ - half, 16);
        final int maxChunkZ = Math.floorDiv(centerZ + half, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                world.getChunkAt(chunkX, chunkZ);
            }
        }
    }

    private void fillChests(final List<Block> chests, final List<CoreConfig.ChestItem> chestItems) {
        if (chests.isEmpty() || chestItems.isEmpty()) {
            return;
        }
        for (final Block chestBlock : chests) {
            final BlockState state = chestBlock.getState();
            if (state instanceof Chest chest) {
                final Inventory inventory = chest.getInventory();
                inventory.clear();
                for (final CoreConfig.ChestItem item : chestItems) {
                    inventory.addItem(new ItemStack(item.material(), item.amount()));
                }
            }
        }
    }
}
