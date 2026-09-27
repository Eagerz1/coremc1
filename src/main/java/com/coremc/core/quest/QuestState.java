package com.coremc.core.quest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mutable quest runtime state, persisted by YamlQuestStore. */
public final class QuestState {

    public static final class Assignment {
        private final String templateId;
        private final Map<String, Long> progress = new LinkedHashMap<>();
        private boolean completed;
        private long completedAt;
        private boolean completionCounted;
        private final Set<String> deliveredRewards = new LinkedHashSet<>();
        private boolean claimed;

        public Assignment(final String templateId) {
            this.templateId = QuestConfig.normalise(templateId);
        }

        public String templateId() {
            return templateId;
        }

        public Map<String, Long> progress() {
            return progress;
        }

        public long progress(final String objectiveId) {
            return progress.getOrDefault(QuestConfig.normalise(objectiveId), 0L);
        }

        public void addProgress(final String objectiveId, final long amount, final long target) {
            final String id = QuestConfig.normalise(objectiveId);
            progress.put(id, Math.min(Math.max(0L, target), progress(id) + Math.max(0L, amount)));
        }

        public void setProgress(final String objectiveId, final long amount) {
            progress.put(QuestConfig.normalise(objectiveId), Math.max(0L, amount));
        }

        public boolean completed() {
            return completed;
        }

        public void setCompleted(final boolean completed, final long at) {
            this.completed = completed;
            if (completed && completedAt <= 0L) {
                this.completedAt = at;
            }
        }

        public long completedAt() {
            return completedAt;
        }

        public void setCompletedAt(final long completedAt) {
            this.completedAt = Math.max(0L, completedAt);
        }

        public boolean completionCounted() {
            return completionCounted;
        }

        public void setCompletionCounted(final boolean completionCounted) {
            this.completionCounted = completionCounted;
        }

        public Set<String> deliveredRewards() {
            return deliveredRewards;
        }

        public boolean claimed() {
            return claimed;
        }

        public void setClaimed(final boolean claimed) {
            this.claimed = claimed;
        }

        public boolean rewardPending(final QuestConfig.QuestTemplate template) {
            if (!completed || claimed || template == null) {
                return false;
            }
            for (final QuestConfig.RewardDef reward : template.rewards()) {
                if (!deliveredRewards.contains(reward.key())) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final class PlayerState {
        private final UUID playerId;
        private long dailyResetAt;
        private long weeklyResetAt;
        private long dailyCycle;
        private long weeklyCycle;
        private int streak;
        private long lastStreakCycle = -1L;
        private boolean onboardingShown;
        private String onboardingStep = "create-island";
        private final Map<String, Assignment> daily = new LinkedHashMap<>();
        private final Map<String, Assignment> weekly = new LinkedHashMap<>();
        private final Set<String> deliveredGeneralRewards = new LinkedHashSet<>();

        public PlayerState(final UUID playerId) {
            this.playerId = playerId;
        }

        public UUID playerId() { return playerId; }
        public long dailyResetAt() { return dailyResetAt; }
        public void setDailyResetAt(final long dailyResetAt) { this.dailyResetAt = Math.max(0L, dailyResetAt); }
        public long weeklyResetAt() { return weeklyResetAt; }
        public void setWeeklyResetAt(final long weeklyResetAt) { this.weeklyResetAt = Math.max(0L, weeklyResetAt); }
        public long dailyCycle() { return dailyCycle; }
        public void setDailyCycle(final long dailyCycle) { this.dailyCycle = Math.max(0L, dailyCycle); }
        public long weeklyCycle() { return weeklyCycle; }
        public void setWeeklyCycle(final long weeklyCycle) { this.weeklyCycle = Math.max(0L, weeklyCycle); }
        public int streak() { return streak; }
        public void setStreak(final int streak) { this.streak = Math.max(0, streak); }
        public long lastStreakCycle() { return lastStreakCycle; }
        public void setLastStreakCycle(final long lastStreakCycle) { this.lastStreakCycle = lastStreakCycle; }
        public boolean onboardingShown() { return onboardingShown; }
        public void setOnboardingShown(final boolean onboardingShown) { this.onboardingShown = onboardingShown; }
        public String onboardingStep() { return onboardingStep; }
        public void setOnboardingStep(final String onboardingStep) {
            this.onboardingStep = onboardingStep == null || onboardingStep.isBlank() ? "create-island" : onboardingStep;
        }
        public Map<String, Assignment> daily() { return daily; }
        public Map<String, Assignment> weekly() { return weekly; }
        public Set<String> deliveredGeneralRewards() { return deliveredGeneralRewards; }
    }

    public static final class IslandChallenges {
        private final UUID islandId;
        private long resetAt;
        private long cycle;
        private final Map<String, Assignment> challenges = new LinkedHashMap<>();
        private final Map<String, Map<UUID, Long>> contributors = new LinkedHashMap<>();
        private final Map<String, Set<UUID>> claimedBy = new LinkedHashMap<>();
        private final Map<String, Set<String>> contributorDeliveredRewards = new LinkedHashMap<>();
        private final Map<String, Set<String>> islandDeliveredRewards = new LinkedHashMap<>();

        public IslandChallenges(final UUID islandId) {
            this.islandId = islandId;
        }

        public UUID islandId() { return islandId; }
        public long resetAt() { return resetAt; }
        public void setResetAt(final long resetAt) { this.resetAt = Math.max(0L, resetAt); }
        public long cycle() { return cycle; }
        public void setCycle(final long cycle) { this.cycle = Math.max(0L, cycle); }
        public Map<String, Assignment> challenges() { return challenges; }
        public Map<UUID, Long> contributors(final String templateId) {
            return contributors.computeIfAbsent(QuestConfig.normalise(templateId), ignored -> new LinkedHashMap<>());
        }
        public Map<String, Map<UUID, Long>> contributors() { return Collections.unmodifiableMap(contributors); }
        public Set<UUID> claimedBy(final String templateId) {
            return claimedBy.computeIfAbsent(QuestConfig.normalise(templateId), ignored -> new LinkedHashSet<>());
        }
        public Map<String, Set<UUID>> claimedBy() { return Collections.unmodifiableMap(claimedBy); }
        public Set<String> contributorDelivered(final String templateId, final UUID playerId) {
            return contributorDeliveredRewards.computeIfAbsent(QuestConfig.normalise(templateId) + ":" + playerId,
                    ignored -> new LinkedHashSet<>());
        }
        public Map<String, Set<String>> contributorDeliveredRewards() {
            return Collections.unmodifiableMap(contributorDeliveredRewards);
        }
        public Set<String> islandDelivered(final String templateId) {
            return islandDeliveredRewards.computeIfAbsent(QuestConfig.normalise(templateId), ignored -> new LinkedHashSet<>());
        }
        public Map<String, Set<String>> islandDeliveredRewards() {
            return Collections.unmodifiableMap(islandDeliveredRewards);
        }
    }
}
