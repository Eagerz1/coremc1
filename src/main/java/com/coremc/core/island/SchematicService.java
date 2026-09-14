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
        // Fill the chests one tick later: setType() has just created the
        // tile entities, and the chest state semantics settle within a tick.
        if (!chests.isEmpty() && !chestItems.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> fillChests(chests, chestItems));
        }
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
        for (final Block chestBlock : chests) {
            if (chestBlock.getType() != Material.CHEST) {
                plugin.getLogger().warning("Starter chest at " + chestBlock.getLocation()
                        + " is no longer a chest; skipped filling.");
                continue;
            }
            fillOneChest(chestBlock, chestItems);
        }
    }

    private void fillOneChest(final Block chestBlock, final List<CoreConfig.ChestItem> chestItems) {
        // Attempt 1 — snapshot semantics: edit the state inventory, then
        // force the state back onto the world.
        final BlockState state = chestBlock.getState();
        if (!(state instanceof Chest chest)) {
            plugin.getLogger().warning("Starter chest at " + chestBlock.getLocation()
                    + " has no chest state (got " + (state == null ? "null" : state.getClass().getName())
                    + "); skipped filling.");
            return;
        }
        final Inventory snapshotInventory = chest.getInventory();
        snapshotInventory.clear();
        for (final CoreConfig.ChestItem item : chestItems) {
            snapshotInventory.addItem(new ItemStack(item.material(), item.amount()));
        }
        final boolean applied = chest.update(true, true);

        // Attempt 2 — live semantics: some implementations back getInventory()
        // with the real tile entity instead of the snapshot. If attempt 1 was
        // wiped by the update (or never applied), a fresh state's inventory
        // write goes straight through — and update() is NOT called, so it
        // cannot be overwritten by a stale snapshot.
        int afterFirst = -1;
        if (chestBlock.getState() instanceof Chest afterChest) {
            final Inventory after = afterChest.getInventory();
            afterFirst = countStacks(after);
            if (afterFirst < chestItems.size()) {
                after.clear();
                for (final CoreConfig.ChestItem item : chestItems) {
                    after.addItem(new ItemStack(item.material(), item.amount()));
                }
            }
        }

        final int finalCount = countStacks(freshInventory(chestBlock));
        if (finalCount >= chestItems.size()) {
            plugin.getLogger().info("Starter chest at " + chestBlock.getLocation() + " filled with "
                    + finalCount + " item stack(s)" + (applied ? "" : " (state update refused, live fill used)")
                    + " [state=" + state.getClass().getSimpleName() + ", inv="
                    + snapshotInventory.getClass().getSimpleName() + ", afterFirst=" + afterFirst + "]");
        } else {
            plugin.getLogger().warning("Starter chest at " + chestBlock.getLocation() + " holds "
                    + finalCount + " item stack(s), expected " + chestItems.size()
                    + " [state=" + state.getClass().getSimpleName() + ", inv="
                    + snapshotInventory.getClass().getSimpleName() + ", applied=" + applied
                    + ", afterFirst=" + afterFirst + "]");
        }
    }

    private Inventory freshInventory(final Block chestBlock) {
        final BlockState state = chestBlock.getState();
        return state instanceof Chest chest ? chest.getInventory() : null;
    }

    private int countStacks(final Inventory inventory) {
        if (inventory == null) {
            return -1;
        }
        int count = 0;
        for (final ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir()) {
                count++;
            }
        }
        return count;
    }
}
