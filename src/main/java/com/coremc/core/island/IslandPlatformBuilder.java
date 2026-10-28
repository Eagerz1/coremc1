package com.coremc.core.island;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;

/** Builds the configured starter platform and chest in an island world. */
public final class IslandPlatformBuilder {

    private static final int PLATFORM_RADIUS = 3;
    private static final List<String> DEFAULT_DECORATIONS = List.of(
            "2,1,2=OAK_SAPLING", "-2,0,-2=WATER", "-1,0,-2=WATER");
    private static final List<String> DEFAULT_CHEST_CONTENTS = List.of(
            "ICE:2", "LAVA_BUCKET:1", "BREAD:4", "BONE_MEAL:3");

    private final Logger logger;

    public IslandPlatformBuilder(final Logger logger) {
        this.logger = logger;
    }

    public void generate(final World world, final Island island, final IslandTheme theme) {
        final int cx = island.centerX();
        final int cy = island.centerY();
        final int cz = island.centerZ();
        final Material top = theme == null ? Material.GRASS_BLOCK : theme.top();
        final Material under = theme == null ? Material.DIRT : theme.under();

        for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
            for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                setBlock(world, cx + dx, cy - 2, cz + dz, under);
                setBlock(world, cx + dx, cy - 1, cz + dz, under);
                setBlock(world, cx + dx, cy, cz + dz, top);
            }
        }
        setBlock(world, cx, cy - 1, cz, Material.BEDROCK);

        final List<Location> saplings = new ArrayList<>();
        final List<String> decorations = theme == null ? DEFAULT_DECORATIONS : theme.decorations();
        for (final String entry : decorations) {
            final Object[] parsed = ThemeService.parseDecoration(entry);
            if (parsed == null) {
                continue;
            }
            final int dx = (Integer) parsed[0];
            final int dy = (Integer) parsed[1];
            final int dz = (Integer) parsed[2];
            final Material material = (Material) parsed[3];
            if (Math.abs(dx) > PLATFORM_RADIUS || Math.abs(dz) > PLATFORM_RADIUS) {
                continue;
            }
            final Location at = new Location(world, cx + dx, cy + dy, cz + dz);
            if (material.name().endsWith("_SAPLING")) {
                saplings.add(at);
            }
            setBlock(world, at.getBlockX(), at.getBlockY(), at.getBlockZ(), material);
        }

        final boolean tree = theme == null || theme.tree();
        if (tree && !saplings.isEmpty()) {
            final Location sapling = saplings.get(0);
            if (!world.generateTree(sapling, TreeType.TREE)) {
                logger.fine("Tree generation reported failure at " + sapling + " — sapling remains.");
            }
        }

        placeStarterChest(world, island, theme == null ? DEFAULT_CHEST_CONTENTS : theme.chestContents());
    }

    private static void placeStarterChest(final World world, final Island island, final List<String> contents) {
        final int x = island.centerX();
        final int y = island.centerY() + 1;
        final int z = island.centerZ() + 2;
        setBlock(world, x, y, z, Material.CHEST);
        final BlockState state = world.getBlockAt(x, y, z).getState();
        if (state instanceof Chest chest) {
            chest.getBlockInventory().clear();
            for (final String entry : contents) {
                final Object[] parsed = ThemeService.parseContent(entry);
                if (parsed != null) {
                    chest.getBlockInventory().addItem(new ItemStack((Material) parsed[0], (Integer) parsed[1]));
                }
            }
            chest.update(true);
        }
    }

    private static void setBlock(final World world, final int x, final int y, final int z, final Material material) {
        final Block block = world.getBlockAt(x, y, z);
        block.setType(material);
    }
}
