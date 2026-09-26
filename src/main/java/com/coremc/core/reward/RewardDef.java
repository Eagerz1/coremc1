package com.coremc.core.reward;

import java.util.List;
import java.util.Locale;
import org.bukkit.Material;

/**
 * One configured reward in a crate or lootbox pool: what it pays
 * ({@link RewardType}), how much ({@code min..max}), how likely
 * ({@code weight} against the pool total), how it is shown (display
 * name, rarity) and — for {@link RewardType#ITEM} — which vanilla
 * material carries the PDC tag, or — for {@link RewardType#COMMAND} —
 * which console commands run.
 *
 * <p>Pure data: rolled by {@link WeightedTable}, rendered by the
 * preview GUIs, granted by the deliverer.</p>
 */
public record RewardDef(
        String id,
        RewardType type,
        String display,
        String rarity,
        long min,
        long max,
        double weight,
        Material material,
        List<String> commands) {

    /** Rarities that count as "rare" for broadcasts and the guaranteed slot. */
    public static final List<String> RARE_RARITIES = List.of("rare", "epic", "legendary");

    public RewardDef {
        commands = commands == null ? List.of() : List.copyOf(commands);
        rarity = rarity == null ? "common" : rarity.toLowerCase(Locale.ROOT);
    }

    /** True when this reward's rarity should broadcast / glow. */
    public boolean rare() {
        return RARE_RARITIES.contains(rarity);
    }

    /** Colour code for the rarity label. */
    public String rarityColor() {
        return switch (rarity) {
            case "legendary" -> "&6";
            case "epic" -> "&5";
            case "rare" -> "&d";
            case "uncommon" -> "&b";
            default -> "&7";
        };
    }

    /** Rolls a concrete amount inside {@code min..max} from a 0..1 roll. */
    public long rollAmount(final double roll) {
        if (max <= min) {
            return min;
        }
        final double clamped = Math.min(0.999999, Math.max(0.0, roll));
        return min + (long) Math.floor(clamped * (max - min + 1));
    }

    /** The amount display: {@code 5,000} or {@code 5,000 - 15,000}. */
    public String amountText() {
        final String low = String.format(Locale.US, "%,d", min);
        if (max <= min) {
            return low;
        }
        return low + " - " + String.format(Locale.US, "%,d", max);
    }
}
