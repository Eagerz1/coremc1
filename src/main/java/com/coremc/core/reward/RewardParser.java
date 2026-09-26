package com.coremc.core.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses reward pools out of {@code crates.yml} / {@code lootboxes.yml}
 * sections. Shared by both configs so a reward means exactly the same
 * thing everywhere. Every problem is collected into the caller's list —
 * an invalid reward is reported loudly and never silently corrupts a
 * purchase.
 *
 * <p>Entry shape (one sub-section per reward):</p>
 * <pre>
 * rewards:
 *   money-small:
 *     type: money
 *     display: "&amp;aMoney Pouch"
 *     rarity: common
 *     min: 5000
 *     max: 15000
 *     weight: 30
 *   river-key:
 *     type: key
 *     id: river
 *     display: "&amp;b&amp;lRiver Key"
 *     rarity: rare
 *     amount: 1
 *     weight: 3
 * </pre>
 */
public final class RewardParser {

    private RewardParser() {
    }

    /**
     * Parses every reward under {@code section}. Unknown types, missing
     * ids, bad ranges and non-positive weights are reported into
     * {@code problems} (with {@code context} naming the pool).
     */
    public static List<RewardDef> parse(final ConfigurationSection section, final String context,
                                        final Set<String> keyIds, final Set<String> lootboxIds,
                                        final List<String> problems) {
        final List<RewardDef> rewards = new ArrayList<>();
        if (section == null) {
            problems.add(context + ": missing rewards section");
            return rewards;
        }
        for (final String rewardId : section.getKeys(false)) {
            final ConfigurationSection entry = section.getConfigurationSection(rewardId);
            if (entry == null) {
                problems.add(context + "." + rewardId + ": not a section");
                continue;
            }
            final RewardDef def = parseOne(entry, context + "." + rewardId, rewardId,
                    keyIds, lootboxIds, problems);
            if (def != null) {
                rewards.add(def);
            }
        }
        if (rewards.isEmpty()) {
            problems.add(context + ": no valid rewards");
        }
        return rewards;
    }

    private static RewardDef parseOne(final ConfigurationSection entry, final String context,
                                      final String rewardId, final Set<String> keyIds,
                                      final Set<String> lootboxIds, final List<String> problems) {
        final RewardType type = RewardType.parse(entry.getString("type"));
        if (type == null) {
            problems.add(context + ": unknown type '" + entry.getString("type") + "'");
            return null;
        }
        final String display = entry.getString("display", "");
        if (display.isBlank()) {
            problems.add(context + ": missing display name");
            return null;
        }
        final double weight = entry.getDouble("weight", -1);
        if (weight <= 0 || !Double.isFinite(weight)) {
            problems.add(context + ": weight must be > 0");
            return null;
        }
        long min = entry.getLong("min", entry.getLong("amount", 1));
        long max = entry.getLong("max", min);
        if (min < 0 || max < min) {
            problems.add(context + ": bad amount range " + min + ".." + max);
            return null;
        }
        if (type == RewardType.COMMAND) {
            min = Math.max(1, min);
            max = Math.max(min, max);
        }
        final String id = entry.getString("id", "").trim().toLowerCase(Locale.ROOT);
        Material material = null;
        List<String> commands = List.of();
        switch (type) {
            case KEY -> {
                if (id.isEmpty() || (keyIds != null && !keyIds.contains(id))) {
                    problems.add(context + ": unknown key id '" + id + "'");
                    return null;
                }
            }
            case LOOTBOX -> {
                if (id.isEmpty() || (lootboxIds != null && !lootboxIds.contains(id))) {
                    problems.add(context + ": unknown lootbox id '" + id + "'");
                    return null;
                }
            }
            case ITEM -> {
                final String materialName = entry.getString("material", "");
                material = Material.matchMaterial(materialName);
                if (material == null || material == Material.AIR) {
                    problems.add(context + ": unknown item material '" + materialName + "'");
                    return null;
                }
                if (id.isEmpty()) {
                    problems.add(context + ": item rewards need an id (the PDC tag)");
                    return null;
                }
            }
            case COMMAND -> {
                commands = entry.getStringList("commands");
                if (commands.isEmpty()) {
                    problems.add(context + ": command rewards need a commands list");
                    return null;
                }
            }
            default -> {
                // currency rewards need nothing else
            }
        }
        // KEY/LOOTBOX/ITEM rewards are identified by their reference id
        // (the key/lootbox id, the item's PDC tag); everything else keeps
        // its section name.
        final String defId = id.isEmpty() ? rewardId : id;
        return new RewardDef(defId, type, display, entry.getString("rarity", "common"),
                min, max, weight, material, commands);
    }
}
