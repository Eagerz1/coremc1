package com.coremc.core.enchant;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;

/**
 * Shared block classification for the enchant activity pipelines
 * (pure statics — no server needed).
 *
 * Type sets are name-based where the family is open-ended
 * (logs/stems/hyphae, leaves, ores) so future materials join
 * automatically, and explicit where the set is a game rule (farm
 * crops, replantables, smelt conversions).
 */
public final class EnchantBlocks {

    /** Blocks the farming pipeline owns (harvestables). */
    public static final Set<Material> FARM_BLOCKS = EnumSet.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.COCOA,
            Material.MELON,
            Material.PUMPKIN,
            Material.SUGAR_CANE,
            Material.CACTUS);

    /** Furnace-parity conversions for the SMELT effect (overridable per enchant). */
    public static final Map<Material, Material> DEFAULT_SMELT = Map.ofEntries(
            Map.entry(Material.RAW_IRON, Material.IRON_INGOT),
            Map.entry(Material.IRON_ORE, Material.IRON_INGOT),
            Map.entry(Material.DEEPSLATE_IRON_ORE, Material.IRON_INGOT),
            Map.entry(Material.RAW_GOLD, Material.GOLD_INGOT),
            Map.entry(Material.GOLD_ORE, Material.GOLD_INGOT),
            Map.entry(Material.DEEPSLATE_GOLD_ORE, Material.GOLD_INGOT),
            Map.entry(Material.RAW_COPPER, Material.COPPER_INGOT),
            Map.entry(Material.COPPER_ORE, Material.COPPER_INGOT),
            Map.entry(Material.DEEPSLATE_COPPER_ORE, Material.COPPER_INGOT),
            Map.entry(Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP),
            Map.entry(Material.SAND, Material.GLASS),
            Map.entry(Material.RED_SAND, Material.GLASS),
            Map.entry(Material.COBBLESTONE, Material.STONE),
            Map.entry(Material.COBBLED_DEEPSLATE, Material.DEEPSLATE),
            Map.entry(Material.CLAY, Material.TERRACOTTA),
            Map.entry(Material.NETHERRACK, Material.NETHER_BRICK),
            Map.entry(Material.CACTUS, Material.GREEN_DYE));

    private EnchantBlocks() {
    }

    /** Logs, stems and hyphae (stripped or not). */
    public static boolean isLog(final Material material) {
        final String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }

    public static boolean isLeaves(final Material material) {
        return material.name().endsWith("_LEAVES");
    }

    /** Anything the logging pipeline owns. */
    public static boolean isWoodLike(final Material material) {
        return isLog(material) || isLeaves(material);
    }

    /** Any ore block plus ancient debris. */
    public static boolean isOre(final Material material) {
        return material == Material.ANCIENT_DEBRIS || material.name().contains("_ORE");
    }

    public static boolean isFarmBlock(final Material material) {
        return FARM_BLOCKS.contains(material);
    }

    /**
     * Whether breaking this farm block counts as a real harvest:
     * ageable crops must be fully grown (anti-abuse: breaking
     * sprouts procs nothing); melons, pumpkins, cane and cactus
     * always count.
     */
    public static boolean isMatureHarvest(final Block block) {
        if (block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() >= ageable.getMaximumAge();
        }
        return true;
    }

    /** Crops REPLANT can resow (directional cocoa and stem fruits excluded). */
    public static boolean isReplantable(final Material material) {
        return material == Material.WHEAT
                || material == Material.CARROTS
                || material == Material.POTATOES
                || material == Material.BEETROOTS
                || material == Material.NETHER_WART;
    }

    /** The seed item consumed to resow {@code crop} (null when not resowable). */
    public static Material seedFor(final Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            default -> null;
        };
    }

    /** The sapling/fungus that regrows {@code log} (null for non-regrowables). */
    public static Material saplingFor(final Material log) {
        String name = log.name();
        if (name.startsWith("STRIPPED_")) {
            name = name.substring("STRIPPED_".length());
        }
        return switch (name) {
            case "OAK_LOG" -> Material.OAK_SAPLING;
            case "SPRUCE_LOG" -> Material.SPRUCE_SAPLING;
            case "BIRCH_LOG" -> Material.BIRCH_SAPLING;
            case "JUNGLE_LOG" -> Material.JUNGLE_SAPLING;
            case "ACACIA_LOG" -> Material.ACACIA_SAPLING;
            case "DARK_OAK_LOG" -> Material.DARK_OAK_SAPLING;
            case "MANGROVE_LOG" -> Material.MANGROVE_PROPAGULE;
            case "CHERRY_LOG" -> Material.CHERRY_SAPLING;
            case "CRIMSON_STEM", "CRIMSON_HYPHAE" -> Material.CRIMSON_FUNGUS;
            case "WARPED_STEM", "WARPED_HYPHAE" -> Material.WARPED_FUNGUS;
            default -> null;
        };
    }

    /**
     * SMELT conversion for one drop: the enchant's
     * {@code values.table} (IN → OUT material names) wins when it
     * names the input, else furnace parity. Null = no conversion.
     */
    public static Material smeltResult(
            final Material input, final Map<String, Object> values, final EnchantEngine engine) {
        final Object table = values.get("table");
        if (table instanceof Map<?, ?> conversions) {
            final Object direct = conversions.get(input.name());
            final Object lowered = direct == null
                    ? conversions.get(input.name().toLowerCase(Locale.ROOT))
                    : direct;
            if (lowered != null) {
                return engine.resolveMaterial(String.valueOf(lowered), null);
            }
        }
        return DEFAULT_SMELT.get(input);
    }
}
