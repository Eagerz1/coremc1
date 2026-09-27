package com.coremc.core.market;

import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;

/**
 * One resolved offer price: Money and/or Sky Tokens and/or Credits.
 * Money is the primary Black Market currency; Sky Tokens ride along
 * on selected offers; Credits appear sparingly on cosmetics only.
 * Amounts are fixed for the whole rotation (resolved once from the
 * configured band when the rotation is selected).
 */
public record MarketCost(long money, long tokens, long credits) {

    public MarketCost {
        if (money < 0 || tokens < 0 || credits < 0) {
            throw new IllegalArgumentException("negative cost");
        }
        if (money == 0 && tokens == 0 && credits == 0) {
            throw new IllegalArgumentException("free offers are not a thing");
        }
    }

    /** True when nothing but Money is charged. */
    public boolean moneyOnly() {
        return tokens == 0 && credits == 0;
    }

    /**
     * The price text without markers:
     * {@code $750,000} / {@code $250,000 + 2,000 ᴛᴏᴋᴇɴs} / {@code 150 ᴄʀᴇᴅɪᴛs}.
     */
    public String text() {
        final List<String> parts = new ArrayList<>(3);
        if (money > 0) {
            parts.add(GuiText.money(money));
        }
        if (tokens > 0) {
            parts.add(GuiText.number(tokens) + " " + GuiText.caps("Tokens"));
        }
        if (credits > 0) {
            parts.add(GuiText.number(credits) + " " + GuiText.caps("Credits"));
        }
        return String.join(" + ", parts);
    }

    /** Compact serialised form for the state file: {@code m:t:c}. */
    public String serialize() {
        return money + ":" + tokens + ":" + credits;
    }

    /** Parses {@link #serialize()}; throws on corrupt input. */
    public static MarketCost parse(final String raw) {
        final String[] parts = (raw == null ? "" : raw).split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("bad cost '" + raw + "'");
        }
        return new MarketCost(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                Long.parseLong(parts[2]));
    }
}
