package com.coremc.core.island;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;

/**
 * The island buff catalogue: 12 permanent island-wide boosts, bought
 * with Sky Tokens in {@code /is buffs}.
 *
 * Buffs are NOT upgrades: upgrades change mechanics (intervals,
 * capacities, tables, unlocks), buffs are flat percentage multipliers
 * applied in the BUFF stage of the effect pipeline (BASE → UPGRADE →
 * BUFF → ROLE → ENCHANT → TEMP). Every buff applies to island members
 * while they stand on their own island — one scope rule, no wild
 * farming, documented on the buff menu.
 *
 * Balance (max tier, costs, percent-per-level) lives in config
 * ({@code island-buffs.<id>}); this file holds only identity and the
 * effect-line templates the GUI renders.
 */
public final class BuffCatalog {

    /** One buff definition (identity + GUI presentation). */
    public record Buff(
            String id,
            String display,
            String description,
            Material icon,
            /** Per-level effect sentence, {@code {pct}} = percent for that level. */
            String effectTemplate) {

        /** Rendered current-effect line for a purchased tier (empty at 0). */
        public String effectText(final int tier, final int pctPerLevel) {
            if (tier <= 0) {
                return "";
            }
            return effectTemplate.replace("{pct}", String.valueOf(tier * pctPerLevel));
        }

        /** Rendered next-level line for the GUI. */
        public String nextText(final int tier, final int pctPerLevel) {
            return effectTemplate.replace("{pct}", "+" + ((tier + 1) * pctPerLevel));
        }
    }

    private static final Map<String, Buff> BUFFS = new LinkedHashMap<>();

    static {
        buff("mining-boost", "&e&lMINING BOOST", "Bigger island mining yields.",
                Material.DIAMOND_PICKAXE, "&7Island mining bonuses &a+{pct}%&7.");
        buff("farming-boost", "&a&lFARMING BOOST", "Bigger island farming yields.",
                Material.GOLDEN_HOE, "&7Island farming bonuses &a+{pct}%&7.");
        buff("fishing-boost", "&b&lFISHING BOOST", "Bigger island fishing yields.",
                Material.FISHING_ROD, "&7Island fishing bonuses &a+{pct}%&7.");
        buff("slaying-boost", "&c&lSLAYING BOOST", "Bigger island slaying yields.",
                Material.IRON_SWORD, "&7Island slaying bonuses &a+{pct}%&7.");
        buff("logging-boost", "&6&lLOGGING BOOST", "Bigger island logging yields.",
                Material.IRON_AXE, "&7Island logging bonuses &a+{pct}%&7.");
        buff("generator-boost", "&6&lGENERATOR BOOST", "Richer generator harvests.",
                Material.OBSERVER, "&7Generator harvests &a+{pct}%&7.");
        buff("spawner-boost", "&d&lSPAWNER BOOST", "Faster island spawners.",
                Material.SPAWNER, "&7Spawner cycles &a{pct}%&7 faster.");
        buff("token-boost", "&b&lTOKEN BOOST", "More Sky Tokens from activity.",
                Material.NETHER_STAR, "&7Sky Token gains &a+{pct}%&7.");
        buff("credit-boost", "&e&lCREDIT BOOST", "More Credits from activity.",
                Material.GOLD_INGOT, "&7Credit gains &a+{pct}%&7.");
        buff("xp-boost", "&a&lXP BOOST", "More role XP from activity.",
                Material.EXPERIENCE_BOTTLE, "&7Role XP gains &a+{pct}%&7.");
        buff("sell-boost", "&a&lSELL BOOST", "Better shop sell prices.",
                Material.EMERALD, "&7Shop sell prices &a+{pct}%&7.");
        buff("island-luck", "&d&lISLAND LUCK", "Luckier rare and treasure rolls.",
                Material.RABBIT_FOOT, "&7Rare/treasure chances &a+{pct}%&7.");
    }

    private BuffCatalog() {
    }

    private static void buff(final String id, final String display, final String description,
            final Material icon, final String effectTemplate) {
        BUFFS.put(id, new Buff(id, display, description, icon, effectTemplate));
    }

    /** All 12 buffs in menu order. */
    public static List<Buff> all() {
        return new ArrayList<>(BUFFS.values());
    }

    /** Buff by id (case-insensitive), or null when unknown. */
    public static Buff byId(final String id) {
        return id == null ? null : BUFFS.get(id.toLowerCase(Locale.ROOT));
    }

    /** Display name for an id (falls back to the raw id). */
    public static String displayOf(final String id) {
        final Buff buff = byId(id);
        return buff == null ? id : buff.display();
    }
}
