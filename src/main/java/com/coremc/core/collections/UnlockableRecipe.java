package com.coremc.core.collections;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Material;

/**
 * A Collection-locked recipe.
 *
 * <p>CoreMC has no custom-recipe system on this branch, so this is the
 * clean requirement service the brief asks for: the recipe states its
 * requirement (a Collection entry and a tier), its ingredients, its
 * output and — per player — whether it is locked or unlocked. The GUI
 * shows all of it, and the unlock is permanent the moment the tier is
 * reached.</p>
 *
 * @param id          stable id, also the reward id used in collections.yml
 * @param display     player-facing name
 * @param icon        GUI icon
 * @param collectionId the Collection entry that unlocks it
 * @param tier        the tier of that entry (1-based)
 * @param ingredients what it costs to craft
 * @param result      what it produces
 * @param resultAmount how many it produces
 * @param description one short line of flavour/utility text
 */
public record UnlockableRecipe(String id, String display, Material icon, String collectionId,
                               int tier, List<Ingredient> ingredients, Material result,
                               int resultAmount, String description) {

    public UnlockableRecipe {
        Objects.requireNonNull(id, "id");
        id = id.trim().toLowerCase(Locale.ROOT);
        display = display == null || display.isBlank() ? id : display.trim();
        collectionId = collectionId == null ? "" : collectionId.trim().toLowerCase(Locale.ROOT);
        tier = Math.max(1, tier);
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        resultAmount = Math.max(1, resultAmount);
        description = description == null ? "" : description.trim();
    }

    /** One ingredient line: a material and how many of it. */
    public record Ingredient(Material material, int amount) {

        public Ingredient {
            Objects.requireNonNull(material, "material");
            amount = Math.max(1, amount);
        }

        /** Readable form, e.g. {@code 9x amethyst shard}. */
        public String text() {
            return amount + "x " + material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    /** Readable output, e.g. {@code 1x amethyst block}. */
    public String resultText() {
        return resultAmount + "x " + result.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
