package com.coremc.core.quest;

import com.coremc.core.config.MessageService;
import com.coremc.core.island.Island;
import com.coremc.core.island.IslandService;
import com.coremc.core.progression.IslandProgressionService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Native CoreMC quest runtime: rotation, objective evaluation, persistence and
 * exactly-once reward delivery. Gameplay listeners only publish actions through
 * QuestProgressService; template matching stays here.
 */
public final class QuestService {

    public enum ClaimResult {
        CLAIMED,
        PENDING,
        NOT_READY,
        ALREADY_CLAIMED,
        NOT_CONTRIBUTOR,
        MISSING
    }

    private final JavaPlugin plugin;
    private final QuestConfig config;
    private final YamlQuestStore store;
    private final IslandService islands;
    private final IslandProgressionService progression;
    private final QuestIntegrationRegistry integrations;
    private final MessageService messages;
    private final Logger logger;
    private final LongSupplier clock;
    private final Map<UUID, QuestState.PlayerState> players = new LinkedHashMap<>();
    private final Map<UUID, QuestState.IslandChallenges> islandChallenges = new LinkedHashMap<>();

    public QuestService(final JavaPlugin plugin, final QuestConfig config, final YamlQuestStore store,
                        final IslandService islands, final IslandProgressionService progression,
                        final QuestIntegrationRegistry integrations, final MessageService messages,
                        final Logger logger) {
        this(plugin, config, store, islands, progression, integrations, messages, logger, System::currentTimeMillis);
    }

    QuestService(final JavaPlugin plugin, final QuestConfig config, final YamlQuestStore store,
                 final IslandService islands, final IslandProgressionService progression,
                 final QuestIntegrationRegistry integrations, final MessageService messages,
                 final Logger logger, final LongSupplier clock) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.islands = islands;
        this.progression = progression;
        this.integrations = integrations;
        this.messages = messages;
        this.logger = logger;
        this.clock = clock;
    }

    public void load() {
        players.clear();
        islandChallenges.clear();
        try {
            final YamlQuestStore.Data data = store.load();
            players.putAll(data.players());
            islandChallenges.putAll(data.islands());
        } catch (final IOException exception) {
            logger.warning("Quest data starts fresh — " + exception.getMessage());
        }
        if (plugin != null && config.enabled()) {
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::persist, 20L * 60L, 20L * 60L);
        }
    }

    public void shutdown() {
        persist();
    }

    public QuestConfig config() {
        return config;
    }

    public QuestIntegrationRegistry integrations() {
        return integrations;
    }

    public QuestState.PlayerState playerState(final UUID playerId) {
        return players.computeIfAbsent(playerId, QuestState.PlayerState::new);
    }

    public QuestState.IslandChallenges islandState(final Island island) {
        return islandChallenges.computeIfAbsent(island.id(), QuestState.IslandChallenges::new);
    }

    public void ensure(final Player player) {
        if (player == null || !config.enabled()) {
            return;
        }
        ensure(player.getUniqueId(), islands == null ? null : islands.islandOf(player.getUniqueId()));
    }

    public void ensure(final UUID playerId, final Island island) {
        if (playerId == null || !config.enabled()) {
            return;
        }
        final QuestState.PlayerState state = playerState(playerId);
        rotatePlayer(state, island, false);
        if (island != null) {
            rotateIsland(islandState(island), island, false);
        }
    }

    public List<QuestState.Assignment> daily(final Player player) {
        ensure(player);
        return List.copyOf(playerState(player.getUniqueId()).daily().values());
    }

    public List<QuestState.Assignment> weekly(final Player player) {
        ensure(player);
        return List.copyOf(playerState(player.getUniqueId()).weekly().values());
    }

    public List<QuestState.Assignment> challenges(final Player player) {
        ensure(player);
        final Island island = islands == null ? null : islands.islandOf(player.getUniqueId());
        if (island == null) {
            return List.of();
        }
        return List.copyOf(islandState(island).challenges().values());
    }

    public void recordAction(final QuestAction action) {
        if (!config.enabled() || action == null || action.amount() <= 0L) {
            return;
        }
        boolean changed = false;
        if (action.playerId() != null) {
            final QuestState.PlayerState player = playerState(action.playerId());
            rotatePlayer(player, action.island(), false);
            changed |= applyToAssignments(player, action, player.daily(), true);
            changed |= applyToAssignments(player, action, player.weekly(), false);
        }
        if (action.island() != null) {
            final QuestState.IslandChallenges island = islandState(action.island());
            rotateIsland(island, action.island(), false);
            changed |= applyToIslandChallenges(island, action);
        }
        if (changed) {
            persist();
        }
    }

    private boolean applyToAssignments(final QuestState.PlayerState state, final QuestAction action,
                                       final Map<String, QuestState.Assignment> assignments,
                                       final boolean daily) {
        boolean changed = false;
        final List<QuestState.Assignment> newlyCompleted = new ArrayList<>();
        for (final QuestState.Assignment assignment : assignments.values()) {
            final QuestConfig.QuestTemplate template = config.template(assignment.templateId());
            if (template == null || assignment.claimed()) {
                continue;
            }
            if (applyProgress(assignment, template, action)) {
                changed = true;
                if (!assignment.completed() && complete(assignment, template)) {
                    assignment.setCompleted(true, now());
                    newlyCompleted.add(assignment);
                }
            }
        }
        for (final QuestState.Assignment assignment : newlyCompleted) {
            if (daily && !assignment.completionCounted()) {
                assignment.setCompletionCounted(true);
                advanceStreak(state);
                applyToAssignments(state, new QuestAction(state.playerId(), action.island(),
                        "daily-completed", 1L, Map.of()), state.weekly(), false);
            }
            final Player online = plugin == null ? null : Bukkit.getPlayer(state.playerId());
            if (online != null && online.isOnline() && messages != null) {
                messages.sendPrefixed(online, "quest.completed", Map.of(
                        "quest", displayName(config.template(assignment.templateId()), assignment.templateId())));
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
            }
        }
        return changed || !newlyCompleted.isEmpty();
    }

    private boolean applyToIslandChallenges(final QuestState.IslandChallenges islandState,
                                            final QuestAction action) {
        boolean changed = false;
        for (final QuestState.Assignment assignment : islandState.challenges().values()) {
            final QuestConfig.QuestTemplate template = config.template(assignment.templateId());
            if (template == null) {
                continue;
            }
            if (applyProgress(assignment, template, action)) {
                changed = true;
                if (action.playerId() != null) {
                    final Map<UUID, Long> contributors = islandState.contributors(assignment.templateId());
                    contributors.put(action.playerId(), contributors.getOrDefault(action.playerId(), 0L)
                            + action.amount());
                }
                if (!assignment.completed() && complete(assignment, template)) {
                    assignment.setCompleted(true, now());
                }
            }
        }
        return changed;
    }

    private boolean applyProgress(final QuestState.Assignment assignment,
                                  final QuestConfig.QuestTemplate template,
                                  final QuestAction action) {
        boolean changed = false;
        for (final QuestConfig.ObjectiveDef objective : template.objectives()) {
            if (objective.matches(action)) {
                final long before = assignment.progress(objective.id());
                assignment.addProgress(objective.id(), action.amount(), objective.target());
                changed |= assignment.progress(objective.id()) != before;
            }
        }
        return changed;
    }

    private boolean complete(final QuestState.Assignment assignment,
                             final QuestConfig.QuestTemplate template) {
        for (final QuestConfig.ObjectiveDef objective : template.objectives()) {
            if (assignment.progress(objective.id()) < objective.target()) {
                return false;
            }
        }
        return true;
    }

    private void advanceStreak(final QuestState.PlayerState state) {
        if (!config.streakEnabled()) {
            return;
        }
        final long cycle = state.dailyCycle();
        if (state.lastStreakCycle() == cycle) {
            return;
        }
        if (state.lastStreakCycle() == cycle - 1L) {
            state.setStreak(state.streak() + 1);
        } else {
            state.setStreak(1);
        }
        state.setLastStreakCycle(cycle);
        deliverStreakMilestones(state, cycle);
        if (state.onboardingStep().equals("complete-daily")) {
            state.setOnboardingStep("gain-island-xp");
        }
    }

    private void deliverStreakMilestones(final QuestState.PlayerState state, final long cycle) {
        final Player player = plugin == null ? null : Bukkit.getPlayer(state.playerId());
        final Island island = islands == null ? null : islands.islandOf(state.playerId());
        for (final QuestConfig.StreakMilestone milestone : config.streakMilestones()) {
            if (milestone.days() <= 0 || state.streak() % milestone.days() != 0) {
                continue;
            }
            for (final QuestConfig.RewardDef reward : milestone.rewards()) {
                final String key = "streak:" + cycle + ":" + milestone.days() + ":" + reward.key();
                if (state.deliveredGeneralRewards().contains(key)) {
                    continue;
                }
                final QuestRewardIntegration.Result result = integrations.reward(reward.type()).deliver(player, island, reward);
                if (result == QuestRewardIntegration.Result.DELIVERED) {
                    state.deliveredGeneralRewards().add(key);
                }
            }
        }
    }

    public ClaimResult claim(final Player player, final QuestConfig.Scope scope, final String questId) {
        if (player == null || !config.enabled()) {
            return ClaimResult.MISSING;
        }
        ensure(player);
        if (scope == QuestConfig.Scope.ISLAND) {
            return claimChallenge(player, questId);
        }
        final QuestState.PlayerState state = playerState(player.getUniqueId());
        final Map<String, QuestState.Assignment> assignments = scope == QuestConfig.Scope.WEEKLY
                ? state.weekly() : state.daily();
        final QuestState.Assignment assignment = assignments.get(QuestConfig.normalise(questId));
        final QuestConfig.QuestTemplate template = config.template(questId);
        if (assignment == null || template == null) {
            return ClaimResult.MISSING;
        }
        if (!assignment.completed()) {
            return ClaimResult.NOT_READY;
        }
        if (assignment.claimed()) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        final Island island = islands == null ? null : islands.islandOf(player.getUniqueId());
        boolean pending = false;
        for (final QuestConfig.RewardDef reward : template.rewards()) {
            if (assignment.deliveredRewards().contains(reward.key())) {
                continue;
            }
            final QuestRewardIntegration.Result result = integrations.reward(reward.type()).deliver(player, island, reward);
            if (result == QuestRewardIntegration.Result.DELIVERED) {
                assignment.deliveredRewards().add(reward.key());
            } else {
                pending = true;
            }
        }
        if (!pending) {
            assignment.setClaimed(true);
            if (messages != null) {
                messages.sendPrefixed(player, "quest.claimed", Map.of("quest", template.name()));
            }
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.3f);
            persist();
            return ClaimResult.CLAIMED;
        }
        if (messages != null) {
            messages.sendPrefixed(player, "quest.reward-pending", Map.of("quest", template.name()));
        }
        persist();
        return ClaimResult.PENDING;
    }

    private ClaimResult claimChallenge(final Player player, final String questId) {
        final Island island = islands == null ? null : islands.islandOf(player.getUniqueId());
        if (island == null) {
            return ClaimResult.MISSING;
        }
        final QuestState.IslandChallenges state = islandState(island);
        final QuestState.Assignment assignment = state.challenges().get(QuestConfig.normalise(questId));
        final QuestConfig.QuestTemplate template = config.template(questId);
        if (assignment == null || template == null) {
            return ClaimResult.MISSING;
        }
        if (!assignment.completed()) {
            return ClaimResult.NOT_READY;
        }
        if (!state.contributors(assignment.templateId()).containsKey(player.getUniqueId())) {
            return ClaimResult.NOT_CONTRIBUTOR;
        }
        if (state.claimedBy(assignment.templateId()).contains(player.getUniqueId())) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        boolean pending = false;
        for (final QuestConfig.RewardDef reward : template.rewards()) {
            final boolean islandOnce = "island-once".equals(reward.scope()) || "shared".equals(reward.scope());
            final java.util.Set<String> delivered = islandOnce
                    ? state.islandDelivered(assignment.templateId())
                    : state.contributorDelivered(assignment.templateId(), player.getUniqueId());
            if (delivered.contains(reward.key())) {
                continue;
            }
            final QuestRewardIntegration.Result result = integrations.reward(reward.type()).deliver(player, island, reward);
            if (result == QuestRewardIntegration.Result.DELIVERED) {
                delivered.add(reward.key());
            } else {
                pending = true;
            }
        }
        if (!pending) {
            state.claimedBy(assignment.templateId()).add(player.getUniqueId());
            if (messages != null) {
                messages.sendPrefixed(player, "quest.claimed", Map.of("quest", template.name()));
            }
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.3f);
            persist();
            return ClaimResult.CLAIMED;
        }
        if (messages != null) {
            messages.sendPrefixed(player, "quest.reward-pending", Map.of("quest", template.name()));
        }
        persist();
        return ClaimResult.PENDING;
    }

    public void reset(final UUID playerId, final QuestConfig.Scope scope) {
        final QuestState.PlayerState state = playerState(playerId);
        if (scope == QuestConfig.Scope.WEEKLY) {
            state.weekly().clear();
            state.setWeeklyResetAt(0L);
        } else {
            state.daily().clear();
            state.setDailyResetAt(0L);
        }
        ensure(playerId, islands == null ? null : islands.islandOf(playerId));
        persist();
    }

    public void reroll(final UUID playerId) {
        final QuestState.PlayerState state = playerState(playerId);
        state.daily().clear();
        state.weekly().clear();
        state.setDailyResetAt(0L);
        state.setWeeklyResetAt(0L);
        ensure(playerId, islands == null ? null : islands.islandOf(playerId));
        persist();
    }

    public boolean complete(final UUID playerId, final String questId) {
        final QuestState.PlayerState state = playerState(playerId);
        ensure(playerId, islands == null ? null : islands.islandOf(playerId));
        QuestState.Assignment assignment = state.daily().get(QuestConfig.normalise(questId));
        if (assignment == null) {
            assignment = state.weekly().get(QuestConfig.normalise(questId));
        }
        final QuestConfig.QuestTemplate template = config.template(questId);
        if (assignment == null || template == null) {
            return false;
        }
        for (final QuestConfig.ObjectiveDef objective : template.objectives()) {
            assignment.setProgress(objective.id(), objective.target());
        }
        assignment.setCompleted(true, now());
        persist();
        return true;
    }

    public String status(final UUID playerId) {
        final QuestState.PlayerState state = playerState(playerId);
        return "daily=" + state.daily().keySet() + ", weekly=" + state.weekly().keySet()
                + ", streak=" + state.streak();
    }

    public void onJoin(final Player player) {
        ensure(player);
        final QuestState.PlayerState state = playerState(player.getUniqueId());
        if (!state.onboardingShown() && messages != null) {
            messages.sendPrefixed(player, "onboarding.welcome");
            messages.sendPrefixed(player, "onboarding.next", Map.of("action", recommendation(player)));
            state.setOnboardingShown(true);
            persist();
        }
    }

    public void onIslandCreated(final Player player) {
        if (player == null) {
            return;
        }
        final QuestState.PlayerState state = playerState(player.getUniqueId());
        if (!"complete-daily".equals(state.onboardingStep())) {
            state.setOnboardingStep("complete-daily");
            persist();
        }
    }

    public String recommendation(final Player player) {
        final QuestState.PlayerState state = playerState(player.getUniqueId());
        final Island island = islands == null ? null : islands.islandOf(player.getUniqueId());
        if (island == null) {
            state.setOnboardingStep("create-island");
            return "Create your island";
        }
        final boolean anyDailyComplete = state.daily().values().stream().anyMatch(QuestState.Assignment::completed);
        if (!anyDailyComplete) {
            state.setOnboardingStep("complete-daily");
            return "Open /quests and complete a Daily Quest";
        }
        state.setOnboardingStep("gain-island-xp");
        return "Gain Island XP and open /is upgrades";
    }

    public boolean hasPendingReward(final QuestState.Assignment assignment) {
        final QuestConfig.QuestTemplate template = config.template(assignment.templateId());
        return assignment.rewardPending(template);
    }

    public long nextDailyReset(final Player player) {
        ensure(player);
        return playerState(player.getUniqueId()).dailyResetAt();
    }

    public long nextWeeklyReset(final Player player) {
        ensure(player);
        return playerState(player.getUniqueId()).weeklyResetAt();
    }

    public long nextIslandReset(final Player player) {
        ensure(player);
        final Island island = islands == null ? null : islands.islandOf(player.getUniqueId());
        return island == null ? 0L : islandState(island).resetAt();
    }

    private void rotatePlayer(final QuestState.PlayerState state, final Island island, final boolean force) {
        final long now = now();
        if (force || state.dailyResetAt() <= now || state.daily().isEmpty()) {
            final long cycle = cycle(now, config.dailyPeriodMillis());
            state.daily().clear();
            state.setDailyCycle(cycle);
            state.setDailyResetAt(nextBoundary(now, config.dailyPeriodMillis()));
            assign(state.daily(), QuestConfig.Scope.DAILY, config.dailyCount(), state.playerId(), island, cycle);
        }
        if (force || state.weeklyResetAt() <= now || state.weekly().isEmpty()) {
            final long cycle = cycle(now, config.weeklyPeriodMillis());
            state.weekly().clear();
            state.setWeeklyCycle(cycle);
            state.setWeeklyResetAt(nextBoundary(now, config.weeklyPeriodMillis()));
            assign(state.weekly(), QuestConfig.Scope.WEEKLY, config.weeklyCount(), state.playerId(), island, cycle);
        }
    }

    private void rotateIsland(final QuestState.IslandChallenges state, final Island island, final boolean force) {
        final long now = now();
        if (force || state.resetAt() <= now || state.challenges().isEmpty()) {
            final long cycle = cycle(now, config.islandChallengePeriodMillis());
            state.challenges().clear();
            state.setCycle(cycle);
            state.setResetAt(nextBoundary(now, config.islandChallengePeriodMillis()));
            assign(state.challenges(), QuestConfig.Scope.ISLAND, config.islandChallengeCount(), island.id(), island, cycle);
        }
    }

    private void assign(final Map<String, QuestState.Assignment> output, final QuestConfig.Scope scope,
                        final int count, final UUID seedId, final Island island, final long cycle) {
        final List<ScoredTemplate> candidates = new ArrayList<>();
        final Random random = new Random(Objects.hash(seedId, scope.name(), cycle));
        for (final QuestConfig.QuestTemplate template : config.templates(scope)) {
            if (!eligible(template, island)) {
                continue;
            }
            final int weight = Math.max(1, template.weight());
            final double score = Math.pow(random.nextDouble(), 1.0D / weight);
            candidates.add(new ScoredTemplate(template, score));
        }
        candidates.sort(Comparator.comparingDouble(ScoredTemplate::score).reversed());
        final List<QuestConfig.QuestTemplate> chosen = new ArrayList<>();
        for (final ScoredTemplate scored : candidates) {
            if (chosen.size() >= count) {
                break;
            }
            if (compatible(chosen, scored.template())) {
                chosen.add(scored.template());
                output.put(scored.template().id(), new QuestState.Assignment(scored.template().id()));
            }
        }
    }

    private boolean eligible(final QuestConfig.QuestTemplate template, final Island island) {
        if (template.weight() <= 0) {
            return false;
        }
        for (final String system : template.requirements().systems()) {
            if (!integrations.systemAvailable(system)) {
                return false;
            }
        }
        if (template.requirements().islandLevel() > 0) {
            if (island == null || progression == null || progression.level(island) < template.requirements().islandLevel()) {
                return false;
            }
        }
        if (!template.requirements().anyMastery().isEmpty()) {
            if (island == null || progression == null) {
                return false;
            }
            boolean any = false;
            for (final String required : template.requirements().anyMastery()) {
                final String[] parts = required.split("\\.", 2);
                if (parts.length == 2 && progression.profile(island).masteryLevel(parts[0], parts[1]) > 0) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private boolean compatible(final List<QuestConfig.QuestTemplate> chosen,
                               final QuestConfig.QuestTemplate candidate) {
        for (final QuestConfig.QuestTemplate existing : chosen) {
            if (existing.incompatible().contains(candidate.id()) || candidate.incompatible().contains(existing.id())) {
                return false;
            }
        }
        return true;
    }

    private long cycle(final long now, final long period) {
        return Math.max(0L, now / Math.max(1L, period));
    }

    private long nextBoundary(final long now, final long period) {
        final long safe = Math.max(1L, period);
        return ((now / safe) + 1L) * safe;
    }

    private long now() {
        return clock.getAsLong();
    }

    private String displayName(final QuestConfig.QuestTemplate template, final String fallback) {
        return template == null ? QuestConfig.title(fallback) : template.name();
    }

    private void persist() {
        try {
            store.save(players, islandChallenges);
        } catch (final IOException exception) {
            logger.warning("Could not save quest data: " + exception.getMessage());
        }
    }

    private record ScoredTemplate(QuestConfig.QuestTemplate template, double score) {
    }

    public OfflinePlayer offline(final String name) {
        return Bukkit.getOfflinePlayer(name);
    }
}
