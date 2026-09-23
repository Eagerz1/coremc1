package com.coremc.core.island;

import java.util.List;
import org.bukkit.Material;

/**
 * A parsed CoreMC island schematic.
 *
 * Format: a hand-editable char-grid — a palette maps single characters
 * to block types, and {@code layers} (bottom to top) hold one string per
 * row (z), one character per column (x). Placements are stored relative
 * to the island centre (x/z) and the island's base Y level (y), with the
 * schematic's {@code origin} offset already applied.
 */
public final class Schematic {

    /** One block to paste, relative to island centre / base Y. */
    public record Placement(int x, int y, int z, Material material) {
    }

    private final String name;
    private final double spawnX;
    private final double spawnY;
    private final double spawnZ;
    private final List<Placement> placements;

    Schematic(final String name, final double spawnX, final double spawnY, final double spawnZ,
              final List<Placement> placements) {
        this.name = name;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.placements = placements;
    }

    public String name() {
        return name;
    }

    /** Spawn x offset from the island centre. */
    public double spawnX() {
        return spawnX;
    }

    /** Spawn height offset from the island base Y. */
    public double spawnY() {
        return spawnY;
    }

    /** Spawn z offset from the island centre. */
    public double spawnZ() {
        return spawnZ;
    }

    public List<Placement> placements() {
        return placements;
    }

    public int blockCount() {
        return placements.size();
    }
}
