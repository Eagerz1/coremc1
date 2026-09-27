package com.coremc.core.collections;

import com.coremc.core.progress.ProgressAction;
import com.coremc.core.progress.ProgressSource;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;

/**
 * One Collection: a stable id, the authoritative action that feeds it,
 * the subject keys it accepts, and its own milestone curve.
 *
 * <p>Hidden entries (discoveries and secrets) render as {@code &8???}
 * with a vague source hint until the player finds one — the hint never
 * names exact odds or an exploitable method.</p>
 *
 * @param id        stable storage id, lower-case
 * @param category  which Collection category it belongs to
 * @param display   player-facing name
 * @param icon      GUI icon
 * @param action    the authoritative action that feeds it
 * @param keys      accepted subject keys, lower-case; empty = any key
 * @param sources   accepted sources; empty = any source
 * @param milestones ascending milestone tiers (never empty)
 * @param hidden    true for discoveries/secrets shown as ??? until found
 * @param hint      vague source hint shown while hidden
 */
public record CollectionEntry(String id, CollectionCategory category, String display, Material icon,
                              ProgressAction action, Set<String> keys, Set<ProgressSource> sources,
                              List<CollectionMilestone> milestones, boolean hidden, String hint) {

    public CollectionEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(action, "action");
        id = id.trim().toLowerCase(Locale.ROOT);
        display = display == null || display.isBlank() ? id : display.trim();
        keys = keys == null ? Set.of() : Set.copyOf(keys);
        sources = sources == null ? Set.of() : Set.copyOf(sources);
        milestones = milestones == null ? List.of() : List.copyOf(milestones);
        hint = hint == null ? "" : hint.trim();
    }

    /** True when this entry accepts the given action/key/source triple. */
    public boolean accepts(final ProgressAction otherAction, final String key,
                           final ProgressSource source) {
        if (action != otherAction) {
            return false;
        }
        if (!keys.isEmpty() && !keys.contains(key == null ? "" : key.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return sources.isEmpty() || sources.contains(source);
    }

    /** The highest milestone amount (the amount that completes the entry). */
    public long finalAmount() {
        return milestones.isEmpty() ? 0 : milestones.get(milestones.size() - 1).amount();
    }

    /** How many tiers this entry has. */
    public int tiers() {
        return milestones.size();
    }

    /** True when this entry tracks a discovery (first-found timestamps and counts). */
    public boolean discovery() {
        return action == ProgressAction.DISCOVERY;
    }
}
