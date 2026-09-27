package com.coremc.core.quest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Atomic YAML persistence for player quest and island challenge state. */
public final class YamlQuestStore {

    public record Data(Map<UUID, QuestState.PlayerState> players,
                       Map<UUID, QuestState.IslandChallenges> islands) {
    }

    private final Path file;
    private final Logger logger;

    public YamlQuestStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public Data load() throws IOException {
        final Map<UUID, QuestState.PlayerState> players = new LinkedHashMap<>();
        final Map<UUID, QuestState.IslandChallenges> islands = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return new Data(players, islands);
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (final Exception exception) {
            logger.warning("Corrupt quest data " + file + " — starting fresh: " + exception.getMessage());
            return new Data(players, islands);
        }
        final ConfigurationSection playerRoot = yaml.getConfigurationSection("players");
        if (playerRoot != null) {
            for (final String key : playerRoot.getKeys(false)) {
                try {
                    final UUID id = UUID.fromString(key);
                    final ConfigurationSection section = playerRoot.getConfigurationSection(key);
                    if (section != null) {
                        players.put(id, readPlayer(id, section));
                    }
                } catch (final IllegalArgumentException exception) {
                    logger.warning("Skipping bad quest player '" + key + "' in " + file + ".");
                }
            }
        }
        final ConfigurationSection islandRoot = yaml.getConfigurationSection("islands");
        if (islandRoot != null) {
            for (final String key : islandRoot.getKeys(false)) {
                try {
                    final UUID id = UUID.fromString(key);
                    final ConfigurationSection section = islandRoot.getConfigurationSection(key);
                    if (section != null) {
                        islands.put(id, readIsland(id, section));
                    }
                } catch (final IllegalArgumentException exception) {
                    logger.warning("Skipping bad quest island '" + key + "' in " + file + ".");
                }
            }
        }
        return new Data(players, islands);
    }

    public void save(final Map<UUID, QuestState.PlayerState> players,
                     final Map<UUID, QuestState.IslandChallenges> islands) throws IOException {
        final Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final QuestState.PlayerState state : players.values()) {
            final String base = "players." + state.playerId();
            yaml.set(base + ".daily-reset-at", state.dailyResetAt());
            yaml.set(base + ".weekly-reset-at", state.weeklyResetAt());
            yaml.set(base + ".daily-cycle", state.dailyCycle());
            yaml.set(base + ".weekly-cycle", state.weeklyCycle());
            yaml.set(base + ".streak", state.streak());
            yaml.set(base + ".last-streak-cycle", state.lastStreakCycle());
            yaml.set(base + ".onboarding.shown", state.onboardingShown());
            yaml.set(base + ".onboarding.step", state.onboardingStep());
            writeAssignments(yaml, base + ".daily", state.daily());
            writeAssignments(yaml, base + ".weekly", state.weekly());
            yaml.set(base + ".delivered-general", state.deliveredGeneralRewards().stream().toList());
        }
        for (final QuestState.IslandChallenges state : islands.values()) {
            final String base = "islands." + state.islandId();
            yaml.set(base + ".reset-at", state.resetAt());
            yaml.set(base + ".cycle", state.cycle());
            writeAssignments(yaml, base + ".challenges", state.challenges());
            for (final Map.Entry<String, Map<UUID, Long>> entry : state.contributors().entrySet()) {
                for (final Map.Entry<UUID, Long> contributor : entry.getValue().entrySet()) {
                    yaml.set(base + ".contributors." + entry.getKey() + "." + contributor.getKey(),
                            contributor.getValue());
                }
            }
            for (final Map.Entry<String, Set<UUID>> entry : state.claimedBy().entrySet()) {
                yaml.set(base + ".claimed-by." + entry.getKey(),
                        entry.getValue().stream().map(UUID::toString).toList());
            }
            for (final Map.Entry<String, Set<String>> entry : state.contributorDeliveredRewards().entrySet()) {
                yaml.set(base + ".contributor-delivered." + entry.getKey(), entry.getValue().stream().toList());
            }
            for (final Map.Entry<String, Set<String>> entry : state.islandDeliveredRewards().entrySet()) {
                yaml.set(base + ".island-delivered." + entry.getKey(), entry.getValue().stream().toList());
            }
        }
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        yaml.save(tmp.toFile());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private QuestState.PlayerState readPlayer(final UUID id, final ConfigurationSection section) {
        final QuestState.PlayerState state = new QuestState.PlayerState(id);
        state.setDailyResetAt(section.getLong("daily-reset-at", 0L));
        state.setWeeklyResetAt(section.getLong("weekly-reset-at", 0L));
        state.setDailyCycle(section.getLong("daily-cycle", 0L));
        state.setWeeklyCycle(section.getLong("weekly-cycle", 0L));
        state.setStreak(section.getInt("streak", 0));
        state.setLastStreakCycle(section.getLong("last-streak-cycle", -1L));
        state.setOnboardingShown(section.getBoolean("onboarding.shown", false));
        state.setOnboardingStep(section.getString("onboarding.step", "create-island"));
        readAssignments(section.getConfigurationSection("daily"), state.daily());
        readAssignments(section.getConfigurationSection("weekly"), state.weekly());
        state.deliveredGeneralRewards().addAll(section.getStringList("delivered-general"));
        return state;
    }

    private QuestState.IslandChallenges readIsland(final UUID id, final ConfigurationSection section) {
        final QuestState.IslandChallenges state = new QuestState.IslandChallenges(id);
        state.setResetAt(section.getLong("reset-at", 0L));
        state.setCycle(section.getLong("cycle", 0L));
        readAssignments(section.getConfigurationSection("challenges"), state.challenges());
        final ConfigurationSection contributors = section.getConfigurationSection("contributors");
        if (contributors != null) {
            for (final String quest : contributors.getKeys(false)) {
                final ConfigurationSection questSection = contributors.getConfigurationSection(quest);
                if (questSection == null) {
                    continue;
                }
                for (final String player : questSection.getKeys(false)) {
                    try {
                        state.contributors(quest).put(UUID.fromString(player), questSection.getLong(player, 0L));
                    } catch (final IllegalArgumentException ignored) {
                        logger.warning("Skipping bad challenge contributor '" + player + "' in " + file + ".");
                    }
                }
            }
        }
        final ConfigurationSection claimed = section.getConfigurationSection("claimed-by");
        if (claimed != null) {
            for (final String quest : claimed.getKeys(false)) {
                for (final String player : claimed.getStringList(quest)) {
                    try {
                        state.claimedBy(quest).add(UUID.fromString(player));
                    } catch (final IllegalArgumentException ignored) {
                        logger.warning("Skipping bad challenge claimant '" + player + "' in " + file + ".");
                    }
                }
            }
        }
        final ConfigurationSection delivered = section.getConfigurationSection("contributor-delivered");
        if (delivered != null) {
            for (final String key : delivered.getKeys(false)) {
                final String[] parts = key.split(":", 2);
                if (parts.length == 2) {
                    try {
                        state.contributorDelivered(parts[0], UUID.fromString(parts[1]))
                                .addAll(delivered.getStringList(key));
                    } catch (final IllegalArgumentException ignored) {
                        logger.warning("Skipping bad delivered reward key '" + key + "' in " + file + ".");
                    }
                }
            }
        }
        final ConfigurationSection islandDelivered = section.getConfigurationSection("island-delivered");
        if (islandDelivered != null) {
            for (final String quest : islandDelivered.getKeys(false)) {
                state.islandDelivered(quest).addAll(islandDelivered.getStringList(quest));
            }
        }
        return state;
    }

    private void readAssignments(final ConfigurationSection section,
                                 final Map<String, QuestState.Assignment> output) {
        if (section == null) {
            return;
        }
        for (final String rawId : section.getKeys(false)) {
            final String id = QuestConfig.normalise(rawId);
            final ConfigurationSection quest = section.getConfigurationSection(rawId);
            if (quest == null) {
                continue;
            }
            final QuestState.Assignment assignment = new QuestState.Assignment(id);
            final ConfigurationSection progress = quest.getConfigurationSection("progress");
            if (progress != null) {
                for (final String objective : progress.getKeys(false)) {
                    assignment.setProgress(objective, progress.getLong(objective, 0L));
                }
            }
            assignment.setCompleted(quest.getBoolean("completed", false), quest.getLong("completed-at", 0L));
            assignment.setCompletedAt(quest.getLong("completed-at", 0L));
            assignment.setCompletionCounted(quest.getBoolean("completion-counted", false));
            assignment.setClaimed(quest.getBoolean("claimed", false));
            assignment.deliveredRewards().addAll(quest.getStringList("delivered-rewards"));
            output.put(id, assignment);
        }
    }

    private void writeAssignments(final YamlConfiguration yaml, final String base,
                                  final Map<String, QuestState.Assignment> assignments) {
        for (final QuestState.Assignment assignment : assignments.values()) {
            final String path = base + "." + assignment.templateId();
            for (final Map.Entry<String, Long> entry : assignment.progress().entrySet()) {
                yaml.set(path + ".progress." + entry.getKey(), entry.getValue());
            }
            yaml.set(path + ".completed", assignment.completed());
            yaml.set(path + ".completed-at", assignment.completedAt());
            yaml.set(path + ".completion-counted", assignment.completionCounted());
            yaml.set(path + ".claimed", assignment.claimed());
            yaml.set(path + ".delivered-rewards", assignment.deliveredRewards().stream().toList());
        }
    }
}
