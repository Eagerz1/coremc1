package com.coremc.core.cosmetic;

import com.coremc.core.role.Role;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Material;

/**
 * One animated skin: a stable id, the target it may be applied to and the
 * presentation model that renders it when the CoreMC resource pack is
 * present.
 *
 * Skins are cosmetic-only by construction: this record carries no damage,
 * enchant, upgrade, level or stat field, so no code path can mutate
 * gameplay through a skin. The underlying item's PDC identity (OmniTool
 * marker, role binding, upgrades) is never rewritten — applying a skin
 * only sets {@code custom_model_data} plus one namespaced cosmetic marker
 * ({@code coremc:tool-skin}).
 *
 * @param id           stable identifier persisted in profiles ({@code emberforge_miner})
 * @param type         TOOL or HAT
 * @param collectionId owning collection ({@code emberforge}) — grouping + theme
 * @param display      legacy-coloured display name
 * @param description  one-line theme description
 * @param role         TOOL only: the single OmniTool role this skin fits (null for hats)
 * @param modelId      stable resource-pack model id (see itemsadder/model-ids.yml)
 * @param material     vanilla base material (safe fallback without the pack)
 * @param source       unlock source id ({@code crate:ember}, {@code store}, {@code event});
 *                     drives the configurable season-reset policy
 */
public record Skin(
        String id,
        SkinType type,
        String collectionId,
        String display,
        String description,
        Role role,
        int modelId,
        Material material,
        String source) {

    public Skin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(collectionId, "collectionId");
        if (id.isBlank() || !id.equals(id.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("skin id must be lowercase, non-blank: " + id);
        }
        if (type == SkinType.TOOL && role == null) {
            throw new IllegalArgumentException("tool skin '" + id + "' needs a role");
        }
        if (type == SkinType.HAT && role != null) {
            throw new IllegalArgumentException("hat skin '" + id + "' must not bind a role");
        }
        if (modelId < 1) {
            throw new IllegalArgumentException("skin model id must be positive: " + id);
        }
        Objects.requireNonNull(material, "material");
        // pure enum comparison on purpose: Material.isAir() is registry-backed
        // on Paper 1.21 and must never run in a bare-JVM unit test
        if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            throw new IllegalArgumentException("skin material must not be air: " + id);
        }
    }

    /** Structural cosmetic-only marker (no stats exist to expose). */
    public boolean cosmeticOnly() {
        return true;
    }

    /** Whether this skin may be applied to a tool bound to {@code role}. */
    public boolean fitsRole(final Role role) {
        return type == SkinType.TOOL && this.role == role;
    }
}
