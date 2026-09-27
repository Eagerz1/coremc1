package com.coremc.core.progress.adapter;

/**
 * The Mining Cube (Island Progression branch). Mining Collections
 * should count generated progression blocks, not hand-placed ones, so
 * the world bridge asks this adapter whether a broken block came from
 * generated content.
 *
 * <p>While the real Mining Cube is absent, the bridge falls back to
 * "not player-placed, inside the island world" — the rule CoreMC
 * already enforces for Mining Essence.</p>
 */
public interface MiningCubeAdapter extends ProgressAdapter {

    MiningCubeAdapter ABSENT = new MiningCubeAdapter() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean isGeneratedBlock(final String world, final int x, final int y, final int z) {
            return false;
        }
    };

    /** True when the block at this position was produced by generated content. */
    boolean isGeneratedBlock(String world, int x, int y, int z);
}
