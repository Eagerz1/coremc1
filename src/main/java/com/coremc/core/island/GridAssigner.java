package com.coremc.core.island;

/**
 * Maps island slots to grid cells in a deterministic spiral around the
 * origin: slot 0 is (0,0), slots 1-8 ring the origin, slots 9-24 form the
 * next ring, and so on. World coordinates of an island centre are the
 * cell multiplied by the configured spacing.
 *
 * The mapping is a pure function of the slot, so the next free slot can
 * simply be {@code max(existing slots) + 1} — slots are never reused,
 * because a deleted island's blocks stay in the world.
 */
public final class GridAssigner {

    private GridAssigner() {
    }

    /** Grid cell (cellX, cellZ) for the given slot. */
    public static int[] cellForSlot(final int slot) {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be >= 0, got " + slot);
        }
        if (slot == 0) {
            return new int[] {0, 0};
        }
        int ring = 1;
        while (ringStart(ring + 1) <= slot) {
            ring++;
        }
        final int offset = slot - ringStart(ring);
        final int side = 2 * ring;
        if (offset < side) {
            return new int[] {ring, -ring + offset};
        }
        if (offset < 2 * side) {
            return new int[] {ring - (offset - side), ring};
        }
        if (offset < 3 * side) {
            return new int[] {-ring, ring - (offset - 2 * side)};
        }
        return new int[] {-ring + (offset - 3 * side), -ring};
    }

    /** World (x, z) block coordinates of the centre of the given slot. */
    public static int[] centerForSlot(final int slot, final int spacing) {
        if (spacing <= 0) {
            throw new IllegalArgumentException("spacing must be > 0, got " + spacing);
        }
        final int[] cell = cellForSlot(slot);
        return new int[] {cell[0] * spacing, cell[1] * spacing};
    }

    /** First slot index of a ring (ring 0 = just slot 0). */
    static int ringStart(final int ring) {
        if (ring <= 0) {
            return 0;
        }
        return 4 * ring * (ring - 1) + 1;
    }
}
