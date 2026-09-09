package com.coremc.core.crate;

import java.util.Objects;

/**
 * One crate-key kind: pure data from the {@code keys:} section of
 * {@code crates.yml}. Keys are physical items (PDC-identified by
 * {@link KeyService}); the crate lineup that consumes them is defined
 * in the same file.
 */
public record CrateKey(String id, String display, String icon) {

    public CrateKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(icon, "icon");
    }
}
