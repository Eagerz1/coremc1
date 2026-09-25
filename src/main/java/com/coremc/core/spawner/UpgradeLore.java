package com.coremc.core.spawner;

import com.coremc.core.essence.EssenceManager;
import com.coremc.core.essence.EssenceType;
import com.coremc.core.shop.Money;
import com.coremc.core.util.ColorUtil;

/**
 * Pure rendering of upgrade requirements: the ✔ / ✖ checklist lines
 * for the Spawner Upgrade GUI and the plain summaries for chat. No
 * Bukkit state — everything needed is passed in, so the output is
 * unit-testable against any player-state permutation.
 */
public final class UpgradeLore {

    private UpgradeLore() {
    }

    /**
     * One checklist line, e.g.
     * {@code &a✔ &7$5,000 &8(Vault Balance)} or
     * {@code &c✖ &720 Slayer Essence &8(You have 12)}.
     *
     * @param requirement    what is required (already stack-scaled)
     * @param have           how much the player has
     * @param ownDropName    display name of the mob's own unique drop
     * @param currencySymbol coin symbol, e.g. "$"
     */
    public static String line(final UpgradeRequirement requirement, final double have,
                              final String ownDropName, final String currencySymbol) {
        final boolean met = have + 1e-9 >= requirement.amount();
        final String tick = met ? "&a✔ " : "&c✖ ";
        final String label = describe(requirement, ownDropName, currencySymbol);
        if (met) {
            return ColorUtil.colorize(tick + "&7" + label
                    + (requirement.type().tag().isEmpty() ? "" : " &8(" + requirement.type().tag() + ")"));
        }
        return ColorUtil.colorize(tick + "&7" + label + " &8(You have "
                + haveText(requirement, have, currencySymbol) + ")");
    }

    /** Plain (uncolored) requirement text, e.g. {@code "$5,000"} or {@code "20 Slayer Essence"}. */
    public static String describe(final UpgradeRequirement requirement, final String ownDropName,
                                  final String currencySymbol) {
        return switch (requirement.type()) {
            case MONEY -> Money.format(requirement.amount(), currencySymbol);
            case KILLS -> EssenceManager.format(requirement.wholeAmount()) + " Mob Kills";
            case ESSENCE -> EssenceManager.format(requirement.wholeAmount()) + " "
                    + (requirement.essence() == null ? EssenceType.SLAYER : requirement.essence()).display();
            case DROP -> requirement.wholeAmount() + "x " + ownDropName;
        };
    }

    /** How a shortfall is shown: money keeps its symbol, everything else is grouped digits. */
    private static String haveText(final UpgradeRequirement requirement, final double have,
                                   final String currencySymbol) {
        return requirement.type() == UpgradeRequirement.Type.MONEY
                ? Money.format(have, currencySymbol)
                : EssenceManager.format((long) Math.floor(have));
    }
}
