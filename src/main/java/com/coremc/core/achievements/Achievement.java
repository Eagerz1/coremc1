package com.coremc.core.achievements;

import com.coremc.core.progress.ProgressAction;
import com.coremc.core.progress.ProgressSource;
import com.coremc.core.progress.reward.Reward;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;

/**
 * One Achievement: what it listens to, how much of it is needed, what
 * it is worth and what it gives.
 *
 * @param id          stable storage id, lower-case
 * @param category    which tab it lives in
 * @param display     player-facing name
 * @param description one short line saying what to do
 * @param icon        GUI icon
 * @param difficulty  tier, drives the points and the colour
 * @param points      Achievement Points (prestige only, never spendable)
 * @param action      the authoritative action it listens to
 * @param keys        accepted subject keys; empty = any key
 * @param sources     accepted sources; empty = any source
 * @param mode        how the events are counted
 * @param requirement how much is needed
 * @param secret      true to hide the details until it is earned
 * @param seasonal    true to stamp the earning season on the record
 * @param rewards     what it gives (permanent unlocks apply instantly)
 */
public record Achievement(String id, AchievementCategory category, String display,
                          String description, Material icon, AchievementDifficulty difficulty,
                          int points, ProgressAction action, Set<String> keys,
                          Set<ProgressSource> sources, AchievementMode mode, long requirement,
                          boolean secret, boolean seasonal, List<Reward> rewards) {

    public Achievement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(mode, "mode");
        id = id.trim().toLowerCase(Locale.ROOT);
        display = display == null || display.isBlank() ? id : display.trim();
        description = description == null ? "" : description.trim();
        keys = keys == null ? Set.of() : Set.copyOf(keys);
        sources = sources == null ? Set.of() : Set.copyOf(sources);
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
        points = Math.max(0, points);
        requirement = Math.max(1, requirement);
    }

    /** True when this Achievement accepts the given action/key/source triple. */
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

    /** True when at least one reward must be claimed by hand. */
    public boolean hasManualReward() {
        return rewards.stream().anyMatch(Reward::manual);
    }

    /** True when everything it gives applies automatically. */
    public boolean automaticOnly() {
        return rewards.isEmpty() || rewards.stream().noneMatch(Reward::manual);
    }
}
