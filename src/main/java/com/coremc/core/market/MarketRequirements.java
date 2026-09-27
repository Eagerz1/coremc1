package com.coremc.core.market;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Requirement checks for gated offers, built as an adapter registry
 * so systems living on OTHER branches (Quests, Season Journey,
 * Collections/Masteries, Achievements) can plug in later without the
 * market knowing them:
 *
 * <pre>plugin.blackMarket().requirements()
 *     .register("collection", (player, value) -&gt; collections.unlocked(player, value));</pre>
 *
 * <p>Built in on this branch: {@code island} (has an island) and
 * {@code island-points:&lt;min&gt;}. An UNKNOWN requirement type fails
 * CLOSED — a gated offer without its integration shows a clear
 * locked line instead of being purchasable (and never crashes).</p>
 */
public final class MarketRequirements {

    /** A pluggable check: true/false verdict, null = cannot answer. */
    public interface Check extends BiFunction<UUID, String, Boolean> {
    }

    private final Map<String, Check> checks = new LinkedHashMap<>();

    /** Registers/overrides the resolver for a requirement type. */
    public void register(final String type, final Check check) {
        checks.put(type.toLowerCase(Locale.ROOT), check);
    }

    /** True when the type has a live resolver. */
    public boolean supported(final String type) {
        return checks.containsKey(type.toLowerCase(Locale.ROOT));
    }

    /**
     * Evaluates one requirement. Unknown types and resolver "cannot
     * answer" both fail closed.
     */
    public boolean met(final UUID player, final MarketOffer.Requirement requirement) {
        final Check check = checks.get(requirement.type().toLowerCase(Locale.ROOT));
        if (check == null) {
            return false;
        }
        try {
            final Boolean verdict = check.apply(player, requirement.value());
            return Boolean.TRUE.equals(verdict);
        } catch (final RuntimeException exception) {
            return false; // a broken integration never unlocks a gate
        }
    }

    /** True when the player meets EVERY requirement of the offer. */
    public boolean allMet(final UUID player, final MarketOffer offer) {
        for (final MarketOffer.Requirement requirement : offer.requirements()) {
            if (!met(player, requirement)) {
                return false;
            }
        }
        return true;
    }

    /** Short lore label for a requirement: {@code ɪsʟᴀɴᴅ ᴘᴏɪɴᴛs 500+}. */
    public static String describe(final MarketOffer.Requirement requirement) {
        return switch (requirement.type().toLowerCase(Locale.ROOT)) {
            case "island" -> "Island required";
            case "island-points" -> "Island points " + requirement.value() + "+";
            case "collection" -> "Collection: " + requirement.value();
            case "mastery" -> "Mastery: " + requirement.value();
            case "season" -> "Season: " + requirement.value();
            default -> requirement.type() + ": " + requirement.value();
        };
    }
}
