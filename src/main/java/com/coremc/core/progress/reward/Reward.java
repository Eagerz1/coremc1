package com.coremc.core.progress.reward;

import com.coremc.core.util.GuiText;
import java.util.Locale;
import java.util.Objects;

/**
 * One reward line from collections.yml or achievements.yml.
 *
 * @param type    what it is
 * @param id      stable subject id (recipe id, title id, material name, …)
 * @param amount  how much (items and currencies only)
 * @param display optional player-facing label; falls back to a sensible one
 */
public record Reward(RewardType type, String id, long amount, String display) {

    public Reward {
        Objects.requireNonNull(type, "type");
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        amount = Math.max(0, amount);
        display = display == null ? "" : display.trim();
    }

    /** A permanent unlock-style reward. */
    public static Reward unlock(final RewardType type, final String id, final String display) {
        return new Reward(type, id, 0, display);
    }

    /** An amount-based reward (items, currencies). */
    public static Reward amount(final RewardType type, final String id, final long amount,
                                final String display) {
        return new Reward(type, id, amount, display);
    }

    /** True when the player must claim this by hand. */
    public boolean manual() {
        return type.manual();
    }

    /** Short player-facing label, already small-capsed where it should be. */
    public String label() {
        if (!display.isEmpty()) {
            return GuiText.caps(display);
        }
        return switch (type) {
            case COINS -> GuiText.money(amount);
            case SKY_TOKENS -> GuiText.number(amount) + " " + GuiText.caps("sky tokens");
            case CREDITS -> GuiText.number(amount) + " " + GuiText.caps("credits");
            case JOURNEY_XP -> GuiText.number(amount) + " " + GuiText.caps("journey xp");
            case KEY -> GuiText.number(amount) + " " + GuiText.caps(pretty(id) + " key");
            case ITEM -> GuiText.number(amount) + "x " + GuiText.caps(pretty(id));
            case RECIPE -> GuiText.caps(pretty(id) + " recipe");
            case COLLECTION_TIER -> GuiText.caps("new " + pretty(id) + " tier");
            default -> GuiText.caps(pretty(id));
        };
    }

    private static String pretty(final String raw) {
        return raw.replace('_', ' ');
    }
}
