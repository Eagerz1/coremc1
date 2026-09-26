package com.coremc.core.gens;

import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure lore rendering for the generator system — the /gens menu
 * entries, the placeable generator item, the management window and
 * the upgrade button.
 *
 * <p>No Bukkit state: everything needed is passed in, so every line
 * (including the live ✔ / ✖ affordability markers) is unit-tested
 * without a server. All output follows the CoreMC GUI design
 * language: short small-caps lore, blank separator lines, coloured
 * values, standard {@code &} codes only.</p>
 */
public final class GeneratorLore {

    private GeneratorLore() {
    }

    /** Item/menu title of a (possibly stacked) generator. */
    public static String title(final GeneratorTier tier, final int amount) {
        final String prefix = amount > 1 ? amount + "x " : "";
        return tier.color() + "&l" + GuiText.caps(prefix + tier.name());
    }

    /** Title of a generator the player cannot buy yet — deliberately dull. */
    public static String lockedTitle(final GeneratorTier tier) {
        return "&8&l" + GuiText.caps(tier.name());
    }

    /** What the generator makes, as one short grey sentence. */
    public static String summary(final GeneratorTier tier) {
        final String what = tier.output() == null
                ? "coins" : friendly(tier.output().name());
        return "&7" + GuiText.caps("Generates " + what + " over time.");
    }

    /**
     * Lore of one entry in the /gens menu.
     *
     * @param tier       the generator
     * @param unlocked   whether the island meets the points requirement
     * @param affordable whether the player can pay the price
     * @param points     the island's current points
     * @param placed     how many of this generator the island has placed
     */
    public static List<String> menu(final GeneratorTier tier, final boolean unlocked,
                                    final boolean affordable, final double points,
                                    final int placed) {
        final List<String> lore = new ArrayList<>();
        lore.add(summary(tier));
        lore.add(GuiText.blank());
        lore.add(GuiText.line("Tier", "&f", tier.tierNumeral()));
        lore.add(GuiText.value("Interval", "&f", GuiText.seconds(tier.intervalSeconds())));
        lore.add(GuiText.value("Gen value", unlocked ? "&a" : "&8", GuiText.money(tier.value())));
        if (tier.requiredPoints() > 0) {
            lore.add(GuiText.requirement("Island points",
                    GuiText.number(tier.requiredPoints()), unlocked));
        }
        lore.add(GuiText.cost("Price", GuiText.money(tier.price()), unlocked && affordable));
        lore.add(GuiText.blank());
        lore.add(GuiText.value("Placed", "&f",
                GuiText.progress(placed, tier.maxPlaced())));
        lore.add(GuiText.blank());
        if (!unlocked) {
            lore.add("&c" + GuiText.caps("Locked")
                    + " &8" + GuiText.caps("— you have " + GuiText.number(points) + " points"));
        } else if (!affordable) {
            lore.add("&c" + GuiText.caps("You cannot afford this yet"));
        } else {
            lore.add(GuiText.click("Click to purchase"));
            lore.add(GuiText.hint("Shift-click to buy 8"));
        }
        return lore;
    }

    /** Lore of the placeable generator item. */
    public static List<String> item(final GeneratorTier tier, final int amount) {
        final List<String> lore = new ArrayList<>();
        lore.add(summary(tier));
        lore.add(GuiText.blank());
        lore.add(GuiText.line("Tier", "&f", tier.tierNumeral()));
        if (amount > 1) {
            lore.add(GuiText.value("Stack", "&f", amount + "x"));
        }
        lore.add(GuiText.value("Interval", "&f", GuiText.seconds(tier.intervalSeconds())));
        lore.add(GuiText.value("Gen value", "&a", GuiText.money(tier.value() * amount)));
        lore.add(GuiText.blank());
        lore.add("&7" + GuiText.caps("Place it on your island to start."));
        lore.add(GuiText.hint(amount > 1
                ? "Sneak-break to take all " + amount
                : "Sneak-click a placed gen to stack"));
        return lore;
    }

    /** Lore of the info panel in the generator management window. */
    public static List<String> manage(final GeneratorTier tier, final int amount,
                                      final String ownerName, final String islandName) {
        final List<String> lore = new ArrayList<>();
        lore.add(summary(tier));
        lore.add(GuiText.blank());
        lore.add(GuiText.line("Tier", "&f", tier.tierNumeral()));
        lore.add(GuiText.value("Stack", "&f", amount + "x"));
        lore.add(GuiText.value("Rate", "&f", GuiText.seconds(tier.intervalSeconds())));
        lore.add(GuiText.value("Gen value", "&a", GuiText.money(tier.value() * amount)));
        lore.add(GuiText.value("Per hour", "&a", GuiText.money(tier.valuePerHour() * amount)));
        lore.add(GuiText.blank());
        lore.add(GuiText.value("Owner", "&f", ownerName));
        lore.add(GuiText.value("Island", "&f", islandName));
        return lore;
    }

    /**
     * Lore of the upgrade button.
     *
     * @param from       the placed generator
     * @param to         the generator it upgrades into (null = maxed)
     * @param stack      how many generators the cost covers
     * @param cost       total upgrade cost
     * @param affordable whether the player can pay it
     * @param unlocked   whether the island meets the target's requirement
     */
    public static List<String> upgrade(final GeneratorTier from, final GeneratorTier to,
                                       final int stack, final double cost,
                                       final boolean affordable, final boolean unlocked) {
        final List<String> lore = new ArrayList<>();
        if (to == null) {
            lore.add("&7" + GuiText.caps(from.name()));
            lore.add(GuiText.blank());
            lore.add("&a" + GuiText.caps("Maximum tier reached."));
            return lore;
        }
        lore.add("&7" + GuiText.caps(from.name()));
        lore.add("&8\u2192");
        lore.add(to.coloredName());
        lore.add(GuiText.blank());
        lore.add(GuiText.value("New rate", "&f", GuiText.seconds(to.intervalSeconds())));
        lore.add(GuiText.value("New value", "&a", GuiText.money(to.value() * stack)));
        if (to.requiredPoints() > 0) {
            lore.add(GuiText.requirement("Island points",
                    GuiText.number(to.requiredPoints()), unlocked));
        }
        lore.add(GuiText.cost("Upgrade cost", GuiText.money(cost), unlocked && affordable));
        lore.add(GuiText.blank());
        if (!unlocked) {
            lore.add("&c" + GuiText.caps("Your island is not ready for this tier"));
        } else if (!affordable) {
            lore.add("&c" + GuiText.caps("You cannot afford this upgrade"));
        } else {
            lore.add(GuiText.click("Click to upgrade"));
            if (stack > 1) {
                lore.add(GuiText.hint("Upgrades all " + stack + " generators"));
            }
        }
        return lore;
    }

    /** Lore of the pick-up button. */
    public static List<String> pickup(final GeneratorTier tier, final int amount,
                                      final boolean allowed) {
        final List<String> lore = new ArrayList<>();
        lore.add("&7" + GuiText.caps("Take this generator back."));
        lore.add(GuiText.blank());
        lore.add(GuiText.value("You receive", "&f",
                GuiText.caps(amount + "x " + tier.name())));
        lore.add(GuiText.blank());
        lore.add(allowed
                ? GuiText.click("Click to pick up")
                : "&c" + GuiText.caps("Only island members can do this"));
        return lore;
    }

    /** Floating label above a placed generator: {@code 8x ɪʀᴏɴ ɢᴇɴᴇʀᴀᴛᴏʀ}. */
    public static String hologram(final GeneratorTier tier, final int amount) {
        final String prefix = amount > 1 ? "&f" + amount + "x " : "";
        return prefix + tier.color() + GuiText.caps(tier.name());
    }

    /** {@code NETHERITE_SCRAP} → {@code netherite scrap}. */
    static String friendly(final String materialName) {
        return materialName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
