package com.coremc.core.achievements;

import com.coremc.core.collections.CollectionLore;
import com.coremc.core.progress.reward.Reward;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;

/**
 * Every line of text the Achievement menus show, as pure functions.
 *
 * <p>Same house style as the rest of CoreMC: short small-caps lore,
 * {@code &} colour codes, blank-line separators, ✔ / ✖ markers, and a
 * clear visual difference between earned, in progress, locked and
 * secret — a secret never looks like something you can work towards
 * until you have earned it.</p>
 */
public final class AchievementLore {

    private AchievementLore() {
    }

    /** Title of a visible achievement, coloured by difficulty. */
    public static String title(final Achievement achievement, final boolean earned) {
        final String color = earned ? "&a" : achievement.difficulty().color();
        return color + "&l" + GuiText.caps(achievement.display());
    }

    /** Title of a secret achievement that has not been earned. */
    public static String secretTitle() {
        return "&8&l" + GuiText.caps("Secret achievement");
    }

    /** The lore of a secret achievement that has not been earned. */
    public static List<String> secret() {
        final List<String> lore = new ArrayList<>();
        lore.add("&8" + GuiText.caps("Hidden until earned"));
        lore.add(GuiText.blank());
        lore.add("&7" + GuiText.caps("Something about the way you"));
        lore.add("&7" + GuiText.caps("play will reveal this one."));
        lore.add(GuiText.blank());
        lore.add(GuiText.hint("No hints. That is the point."));
        return lore;
    }

    /** The lore of a normal achievement. */
    public static List<String> achievement(final Achievement achievement, final long progress,
                                           final boolean earned, final boolean claimable,
                                           final int season) {
        final List<String> lore = new ArrayList<>();
        if (!achievement.description().isBlank()) {
            lore.add("&7" + GuiText.caps(achievement.description()));
            lore.add(GuiText.blank());
        }
        lore.add(GuiText.line("Difficulty", achievement.difficulty().color(),
                achievement.difficulty().display()));
        lore.add(GuiText.value("Points", "&e", GuiText.number(achievement.points())));
        lore.add(GuiText.blank());
        if (earned) {
            lore.add("&a" + GuiText.caps("Earned") + " &a" + GuiText.TICK);
            if (achievement.seasonal() && season >= 0) {
                lore.add(GuiText.value("Season", "&6", String.valueOf(season)));
            }
        } else {
            lore.add(GuiText.value("Progress", "&f",
                    GuiText.number(progress) + "&7/&f" + GuiText.number(achievement.requirement())));
            lore.add(CollectionLore.bar(fraction(progress, achievement.requirement())));
        }
        if (!achievement.rewards().isEmpty()) {
            lore.add(GuiText.blank());
            lore.add("&7" + GuiText.caps("Rewards") + ":");
            for (final Reward reward : achievement.rewards()) {
                lore.add(" &8• " + (reward.manual() ? "&e" : "&a") + reward.label());
            }
        }
        lore.add(GuiText.blank());
        if (claimable) {
            lore.add(GuiText.click("Click to claim"));
        } else if (earned && achievement.hasManualReward()) {
            lore.add("&8" + GuiText.caps("Already claimed"));
        } else if (!earned) {
            lore.add("&c" + GuiText.caps("Not earned yet") + " &c" + GuiText.CROSS);
        }
        return lore;
    }

    /** The lore of a category button. */
    public static List<String> category(final AchievementCategory category, final int total,
                                        final int earned, final int points, final int claimable) {
        final List<String> lore = new ArrayList<>();
        lore.add(GuiText.value("Earned", "&f", GuiText.progress(earned, total)));
        lore.add(GuiText.value("Points", "&e", GuiText.number(points)));
        lore.add(GuiText.blank());
        lore.add(CollectionLore.bar(fraction(earned, total)));
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
    public static List<String> panel(final int points, final int maxPoints, final int earned,
                                     final int total, final int claimable, final int pending) {
        final List<String> lore = new ArrayList<>();
        lore.add(GuiText.value("Achievement points", "&e", GuiText.number(points)));
        lore.add(GuiText.value("Earned", "&f", GuiText.progress(earned, total)));
        lore.add(GuiText.blank());
        lore.add(CollectionLore.bar(fraction(points, maxPoints)));
        lore.add(GuiText.blank());
        lore.add(claimable > 0
                ? "&6" + GuiText.caps(claimable + " reward" + (claimable == 1 ? "" : "s")
                        + " waiting") + " &6" + GuiText.TICK
                : "&8" + GuiText.caps("Nothing to claim right now"));
        if (pending > 0) {
            lore.add("&e" + GuiText.caps(pending + " held safely for you"));
        }
        lore.add(GuiText.blank());
        lore.add(GuiText.hint("Points are prestige, not currency."));
        lore.add(GuiText.hint("They survive every season reset."));
        return lore;
    }

    /** Progress as a 0..1 fraction, guarding against a zero requirement. */
    public static double fraction(final long progress, final long requirement) {
        if (requirement <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(1.0, (double) progress / requirement));
    }
}
