package com.coremc.core.crate;

import java.util.List;
import org.bukkit.Material;

/**
 * One of CoreMC's five physical crate keys (Vote, River, Sky, Crimson,
 * Boost): id, coloured display name, item material, store price in
 * Credits, whether the store sells it, and its short description lines.
 *
 * <p>Real keys are identified by PDC only ({@link KeyItems}) — a
 * renamed or crafted vanilla item is never a key.</p>
 */
public record KeyDef(
        String id,
        String name,
        Material material,
        long price,
        boolean sellable,
        List<String> description) {

    public KeyDef {
        description = description == null ? List.of() : List.copyOf(description);
    }
}
