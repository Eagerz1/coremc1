package com.coremc.core.role;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.bukkit.Material;

/**
 * OmniTool purchase catalogue + upgrade math. Pure Java (no server
 * runtime) so every rule is unit-testable; the Bukkit config parse lives
 * in {@link OmniToolService#load()}.
 *
 * Upgrades are bought per player (profile), not per item: the soulbound
 * tool is re-stamped whenever levels change and on every re-grant, so a
 * replaced tool can never lose purchased investment.
 */
public final class OmniUpgradeCatalog {

    // ---- upgrade catalogue ids (config keys + profile keys) ----
    public static final String EFFICIENCY = "efficiency";
    public static final String FORTUNE = "fortune";
    public static final String SMELTER = "smelter";
    /** GUI display order (OmniToolGui slots 47/49/51). */
    public static final List<String> GUI_ORDER = List.of(EFFICIENCY, FORTUNE, SMELTER);
    // -------------------------------------------------------------

    private final Map<String, Upgrade> upgrades;

    public OmniUpgradeCatalog(final Map<String, Upgrade> upgrades) {
        this.upgrades = Map.copyOf(upgrades);
    }

    public Optional<Upgrade> upgrade(final String id) {
        return Optional.ofNullable(upgrades.get(id));
    }

    public Map<String, Upgrade> all() {
        return upgrades;
    }

    public int size() {
        return upgrades.size();
    }

    /** Immutable upgrade definition loaded from {@code config.yml omnitool.upgrades:<id>}. */
    public record Upgrade(String id, String display, int maxLevel, List<Long> costs) {

        public Upgrade {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Upgrade id must not be blank");
            }
            if (maxLevel < 1) {
                throw new IllegalArgumentException("Upgrade '" + id + "' needs max-level >= 1");
            }
            costs = List.copyOf(costs == null ? List.of() : costs);
            if (costs.size() != maxLevel) {
                throw new IllegalArgumentException("Upgrade '" + id + "' needs exactly " + maxLevel
                        + " costs (one per level), got " + costs.size());
            }
            for (final long cost : costs) {
                if (cost < 0L) {
                    throw new IllegalArgumentException("Upgrade '" + id + "' has a negative cost");
                }
            }
        }

        /** Price to advance from {@code currentLevel} to the next level, or -1 when maxed. */
        public long costForNextLevel(final int currentLevel) {
            if (currentLevel < 0 || currentLevel >= maxLevel) {
                return -1L;
            }
            return costs.get(currentLevel);
        }

        public boolean maxed(final int currentLevel) {
            return currentLevel >= maxLevel;
        }
    }

    // ------------------------------------------------------------------
    // Auto-Smelter table — what a block becomes when the smelt upgrade
    // is owned. null = untouched (fully vanilla drops).
    // ------------------------------------------------------------------

    /** Smelted product for {@code source}, or null when the block smelts to nothing.
     * Raw-block forms (IRON_BLOCK, RAW_IRON_BLOCK, …) are deliberately NOT in the
     * table — only mined ores and cobblestone. */
    public static Material smeltedResult(final Material source) {
        return source == null ? null : SMELT_TABLE.get(source);
    }

    private static final Map<Material, Material> SMELT_TABLE = Map.of(
            Material.IRON_ORE, Material.IRON_INGOT,
            Material.DEEPSLATE_IRON_ORE, Material.IRON_INGOT,
            Material.GOLD_ORE, Material.GOLD_INGOT,
            Material.DEEPSLATE_GOLD_ORE, Material.GOLD_INGOT,
            Material.COPPER_ORE, Material.COPPER_INGOT,
            Material.DEEPSLATE_COPPER_ORE, Material.COPPER_INGOT,
            Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP,
            Material.COBBLESTONE, Material.STONE);

    /**
     * Smelter ergonomics: experience comparable to hand-smelting the ore
     * (whole-number approximations of the furnace values).
     */
    public static int smeltXpFor(final Material source) {
        if (source == null) {
            return 0;
        }
        return switch (source) {
            case ANCIENT_DEBRIS -> 2;
            case IRON_ORE, DEEPSLATE_IRON_ORE,
                    GOLD_ORE, DEEPSLATE_GOLD_ORE,
                    COPPER_ORE, DEEPSLATE_COPPER_ORE -> 1;
            default -> 0;
        };
    }

    // ------------------------------------------------------------------
    // Fortune-on-smelting math — extra ingots beyond the guaranteed one.
    // Curve mirrors vanilla ore fortune: with level L the roll is
    // uniform(0, L+2) and every value above 1 yields an extra.
    //   L0:                always 0 extra
    //   L1: rolls [0,1,2]  -> avg +0.33
    //   L2: rolls [0..3]   -> avg +0.75
    //   L3: rolls [0..4]   -> avg +1.20
    // ------------------------------------------------------------------

    public static int fortuneRollAmount(final int fortuneLevel, final Random random) {
        if (fortuneLevel <= 0) {
            return 0;
        }
        return Math.max(0, random.nextInt(fortuneLevel + 2) - 1);
    }
}
