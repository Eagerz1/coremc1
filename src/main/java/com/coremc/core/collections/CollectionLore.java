package com.coremc.core.collections;

import com.coremc.core.progress.reward.Reward;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;

/**
 * Every line of text the Collection menus show, built as pure
 * functions so the wording, the ✔ / ✖ markers and the hidden-entry
 * rules are all unit-tested.
 *
 * <p>House style, same as the rest of CoreMC: short small-caps lore,
 * {@code &} colour codes only, blank lines between blocks, grey
 * labels with coloured values, yellow call to action, dark grey for
 * anything locked — a locked Collection never looks like an unlocked
 * one.</p>
 */
public final class CollectionLore {

    /** How many characters wide a progress bar is. */
    public static final int BAR_WIDTH = 20;

    private CollectionLore() {
    }

    // ------------------------------------------------------------------
    // titles
    // ------------------------------------------------------------------

    /** Title of a visible entry: {@code &bCᴏʙʙʟᴇsᴛᴏɴᴇ &8[III]}. */
    public static String title(final CollectionEntry entry, final int tier) {
        final String suffix = tier > 0 ? " &8[" + GuiText.roman(tier) + "]" : "";
        return entry.category().color() + "&l" + GuiText.caps(entry.display()) + suffix;
    }

    /** Title of a hidden entry the player has not found yet. */
    public static String hiddenTitle() {
        return "&8&l" + GuiText.caps("? ? ?");
    }

    // ------------------------------------------------------------------
    // lore
    // ------------------------------------------------------------------

    /**
     * The lore of a hidden entry: a vague hint only. Never the drop
     * chance, never the exact method — enough to make a player curious
     * and nothing an exploiter can farm.
     */
    public static List<String> hidden(final CollectionEntry entry) {
        final List<String> lore = new ArrayList<>();
        lore.add("&8" + GuiText.caps("Undiscovered"));
        lore.add(GuiText.blank());
        if (!entry.hint().isBlank()) {
            lore.add("&7" + GuiText.caps(entry.hint()));
            lore.add(GuiText.blank());
        }
        lore.add(GuiText.hint("Find one to reveal this."));
        return lore;
    }

    /** The lore of an entry in a category page. */
    public static List<String> entry(final CollectionEntry entry, final long amount,
                                     final int claimable) {
        final List<String> lore = new ArrayList<>();
        final int tier = CollectionProgress.tiersReached(amount, entry.milestones());
        final CollectionMilestone next = CollectionProgress.next(amount, entry.milestones());
        lore.add(GuiText.value("Collected", "&f", GuiText.number(amount)));
        lore.add(GuiText.value("Tier", "&e", GuiText.progress(tier, entry.tiers())));
        lore.add(GuiText.blank());
        if (next == null) {
            lore.add("&a" + GuiText.caps("Collection complete") + " &a" + GuiText.TICK);
        } else {
            lore.add(GuiText.value("Next tier", "&f",
                    GuiText.number(amount) + "&7/&f" + GuiText.number(next.amount())));
            lore.add(bar(CollectionProgress.tierFraction(amount, entry.milestones())));
        }
        if (claimable > 0) {
            lore.add(GuiText.blank());
            lore.add("&6" + GuiText.caps(claimable + " reward"
                    + (claimable == 1 ? "" : "s") + " to claim") + " &6" + GuiText.TICK);
        }
        lore.add(GuiText.blank());
        lore.add(GuiText.click("Click to view milestones"));
        return lore;
    }

    /** The lore of the big entry panel in the detail view. */
    public static List<String> detail(final CollectionEntry entry, final long amount,
                                      final DiscoveryRecord discovery) {
        final List<String> lore = new ArrayList<>();
        final int tier = CollectionProgress.tiersReached(amount, entry.milestones());
        lore.add(GuiText.line("Category", "&f", entry.category().display()));
        lore.add(GuiText.value("Collected", "&f", GuiText.number(amount)));
        lore.add(GuiText.value("Tier", "&e", GuiText.progress(tier, entry.tiers())));
        lore.add(GuiText.value("Completion", "&b",
                CollectionProgress.percent(CollectionProgress.entryCompletion(amount, entry))
                        + "%"));
        lore.add(GuiText.blank());
        lore.add(bar(CollectionProgress.entryCompletion(amount, entry)));
        if (entry.discovery() && discovery != null && discovery.discovered()) {
            lore.add(GuiText.blank());
            lore.add(GuiText.value("First found", "&f", ago(discovery.first())));
        }
        lore.add(GuiText.blank());
        lore.add(GuiText.hint("Progress here is permanent."));
        lore.add(GuiText.hint("Season resets never touch it."));
        return lore;
    }

    /** The lore of one milestone tier in the detail view. */
    public static List<String> milestone(final CollectionEntry entry,
                                         final CollectionMilestone milestone, final long amount,
                                         final boolean claimed) {
        final boolean reached = amount >= milestone.amount();
        final List<String> lore = new ArrayList<>();
        lore.add(GuiText.requirement("Requires", GuiText.number(milestone.amount()) + " collected",
                reached));
        if (!reached) {
            lore.add(GuiText.value("You have", "&f", GuiText.number(amount)));
        }
        lore.add(GuiText.blank());
        if (milestone.rewards().isEmpty()) {
            lore.add("&8" + GuiText.caps("No reward — pride only."));
        } else {
            lore.add("&7" + GuiText.caps("Rewards") + ":");
            for (final Reward reward : milestone.rewards()) {
                lore.add(" &8• " + (reward.manual() ? "&e" : "&a") + reward.label()
                        + (reward.manual() ? "" : " &a" + GuiText.TICK));
            }
        }
        lore.add(GuiText.blank());
        if (!reached) {
            lore.add("&c" + GuiText.caps("Locked") + " &c" + GuiText.CROSS);
        } else if (!milestone.hasManualReward()) {
            lore.add("&a" + GuiText.caps("Unlocked") + " &a" + GuiText.TICK);
        } else if (claimed) {
            lore.add("&8" + GuiText.caps("Already claimed"));
        } else {
            lore.add(GuiText.click("Click to claim"));
        }
        return lore;
    }

    /** The lore of a category button on the root menu. */
    public static List<String> category(final CollectionCategory category, final int entries,
                                        final int complete, final int percent,
                                        final int claimable) {
        final List<String> lore = new ArrayList<>();
        lore.add(GuiText.value("Collections", "&f", GuiText.progress(complete, entries)));
        lore.add(GuiText.value("Completion", "&b", percent + "%"));
        lore.add(GuiText.blank());
        lore.add(bar(percent / 100.0));
        if (claimable > 0) {
            lore.add(GuiText.blank());
            lore.add("&6" + GuiText.caps(claimable + " reward"
                    + (claimable == 1 ? "" : "s") + " to claim") + " &6" + GuiText.TICK);
        }
        lore.add(GuiText.blank());
        lore.add(GuiText.click("Click to open"));
        return lore;
    }

    /** The lore of the player panel. */
    public static List<String> panel(final int percent, final int complete, final int total,
                                     final int claimable, final int pending) {
        final List<String> lore = new ArrayList<>();
        lore.add(GuiText.value("Completion", "&b", percent + "%"));
        lore.add(GuiText.value("Completed", "&f", GuiText.progress(complete, total)));
        lore.add(GuiText.blank());
        lore.add(bar(percent / 100.0));
        lore.add(GuiText.blank());
        lore.add(claimable > 0
                ? "&6" + GuiText.caps(claimable + " reward" + (claimable == 1 ? "" : "s")
                        + " waiting") + " &6" + GuiText.TICK
                : "&8" + GuiText.caps("Nothing to claim right now"));
        if (pending > 0) {
            lore.add("&e" + GuiText.caps(pending + " held safely for you"));
        }
        lore.add(GuiText.blank());
        lore.add(GuiText.hint("Collections are permanent."));
        return lore;
    }

    /** The lore of a Collection-locked recipe. */
    public static List<String> recipe(final UnlockableRecipe recipe, final CollectionEntry entry,
                                      final int tier, final boolean unlocked) {
        final List<String> lore = new ArrayList<>();
        if (!recipe.description().isBlank()) {
            lore.add("&7" + GuiText.caps(recipe.description()));
            lore.add(GuiText.blank());
        }
        lore.add("&7" + GuiText.caps("Needs") + ":");
        for (final UnlockableRecipe.Ingredient ingredient : recipe.ingredients()) {
            lore.add(" &8• &f" + GuiText.caps(ingredient.text()));
        }
        lore.add(GuiText.value("Makes", "&a", GuiText.caps(recipe.resultText())));
        lore.add(GuiText.blank());
        final String requirement = (entry == null ? recipe.collectionId() : entry.display())
                + " " + GuiText.roman(recipe.tier());
        lore.add(GuiText.requirement("Unlocked by", requirement, unlocked));
        if (!unlocked && entry != null) {
            lore.add(GuiText.value("Your tier", "&f", GuiText.progress(tier, entry.tiers())));
        }
        lore.add(GuiText.blank());
        lore.add(unlocked
                ? "&a" + GuiText.caps("Unlocked") + " &a" + GuiText.TICK
                : "&c" + GuiText.caps("Locked") + " &c" + GuiText.CROSS);
        return lore;
    }

    /** A 20-character progress bar: {@code &a■■■■■■&8■■■■■■■■■■■■■■ &f30%}. */
    public static String bar(final double fraction) {
        final double safe = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
        final int filled = (int) Math.round(safe * BAR_WIDTH);
        final StringBuilder bar = new StringBuilder(safe >= 1.0 ? "&a" : "&b");
        bar.append("\u25a0".repeat(filled));
        bar.append("&8").append("\u25a0".repeat(BAR_WIDTH - filled));
        bar.append(" &f").append((int) Math.floor(safe * 100)).append('%');
        return bar.toString();
    }

    /** Rough "how long ago", used for discovery timestamps. */
    public static String ago(final long millis) {
        if (millis <= 0) {
            return "unknown";
        }
        final long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000L);
        if (seconds < 60) {
            return "just now";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m ago";
        }
        if (seconds < 86_400) {
            return seconds / 3600 + "h ago";
        }
        return seconds / 86_400 + "d ago";
    }
}
