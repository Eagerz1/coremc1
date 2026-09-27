package com.coremc.core.cosmetic;

import java.util.Objects;

/**
 * Immutable cosmetic selection. A skin has no gameplay fields by design:
 * it can only name its target and presentation model. Generator and companion
 * progression data remains on the base object that receives the visual layer.
 */
public record CosmeticSkin(String id, Target target, int modelId) {

    public enum Target {
        GENERATOR,
        COMPANION
    }

    public CosmeticSkin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(target, "target");
        if (id.isBlank()) {
            throw new IllegalArgumentException("skin id must not be blank");
        }
        if (modelId < 1) {
            throw new IllegalArgumentException("skin model id must be positive");
        }
    }

    /** The policy is structural: there is no API here for stats or traits. */
    public boolean cosmeticOnly() {
        return true;
    }
}
