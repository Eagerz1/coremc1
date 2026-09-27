package com.coremc.core.progression;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent island progression state: XP, Sky Tokens, purchased mastery nodes,
 * active/owned Island Core modules, discovery counters and source cap windows. */
public final class IslandProgressionProfile {

    /** Rolling cap window for one XP source. */
    public static final class SourceWindow {
        private long startedAt;
        private double xp;

        public SourceWindow(final long startedAt, final double xp) {
            this.startedAt = Math.max(0L, startedAt);
            this.xp = Math.max(0.0D, xp);
        }

        public long startedAt() {
            return startedAt;
        }

        public double xp() {
            return xp;
        }

        public void reset(final long now) {
            this.startedAt = now;
            this.xp = 0.0D;
        }

        public void addXp(final double amount) {
            this.xp += Math.max(0.0D, amount);
        }
    }

    private final UUID islandId;
    private long xp;
    private long skyTokens;
    private final Map<String, Map<String, Integer>> mastery = new LinkedHashMap<>();
    private final Set<String> ownedModules = new LinkedHashSet<>();
    private final Set<String> activeModules = new LinkedHashSet<>();
    private long lastModuleSwapAt;
    private String fortuneFocus = "";
    private long fortuneSelectedAt;
    private double momentum;
    private final Map<String, Long> activeStates = new LinkedHashMap<>();
    private final Map<String, Long> cooldowns = new LinkedHashMap<>();
    private final Map<String, Integer> discoveries = new LinkedHashMap<>();
    private final Map<String, SourceWindow> sourceWindows = new LinkedHashMap<>();

    public IslandProgressionProfile(final UUID islandId) {
        this.islandId = islandId;
    }

    public UUID islandId() {
        return islandId;
    }

    public long xp() {
        return xp;
    }

    public void setXp(final long xp) {
        this.xp = Math.max(0L, xp);
    }

    public void addXp(final long amount) {
        if (amount > 0L) {
            this.xp += amount;
        }
    }

    public long skyTokens() {
        return skyTokens;
    }

    public void setSkyTokens(final long skyTokens) {
        this.skyTokens = Math.max(0L, skyTokens);
    }

    public void addSkyTokens(final long amount) {
        if (amount > 0L) {
            this.skyTokens += amount;
        }
    }

    public boolean takeSkyTokens(final long amount) {
        if (amount <= 0L) {
            return true;
        }
        if (skyTokens < amount) {
            return false;
        }
        skyTokens -= amount;
        return true;
    }

    public int masteryLevel(final String branch, final String upgrade) {
        return mastery.getOrDefault(branch, Map.of()).getOrDefault(upgrade, 0);
    }

    public void setMasteryLevel(final String branch, final String upgrade, final int level) {
        if (level <= 0) {
            final Map<String, Integer> branchMap = mastery.get(branch);
            if (branchMap != null) {
                branchMap.remove(upgrade);
                if (branchMap.isEmpty()) {
                    mastery.remove(branch);
                }
            }
            return;
        }
        mastery.computeIfAbsent(branch, ignored -> new LinkedHashMap<>()).put(upgrade, level);
    }

    public Map<String, Map<String, Integer>> mastery() {
        final Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, Integer>> entry : mastery.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    public Set<String> ownedModules() {
        return Collections.unmodifiableSet(ownedModules);
    }

    public void addOwnedModule(final String module) {
        if (module != null && !module.isBlank()) {
            ownedModules.add(module);
        }
    }

    public Set<String> activeModules() {
        return Collections.unmodifiableSet(activeModules);
    }

    public void setActiveModules(final Set<String> modules) {
        activeModules.clear();
        activeModules.addAll(modules);
    }

    public long lastModuleSwapAt() {
        return lastModuleSwapAt;
    }

    public void setLastModuleSwapAt(final long lastModuleSwapAt) {
        this.lastModuleSwapAt = Math.max(0L, lastModuleSwapAt);
    }

    public String fortuneFocus() {
        return fortuneFocus;
    }

    public void setFortuneFocus(final String fortuneFocus, final long selectedAt) {
        this.fortuneFocus = fortuneFocus == null ? "" : fortuneFocus;
        this.fortuneSelectedAt = Math.max(0L, selectedAt);
    }

    public long fortuneSelectedAt() {
        return fortuneSelectedAt;
    }

    public double momentum() {
        return momentum;
    }

    public void setMomentum(final double momentum) {
        this.momentum = Math.max(0.0D, momentum);
    }

    public void addMomentum(final double amount, final double max) {
        if (amount > 0.0D) {
            this.momentum = Math.min(Math.max(0.0D, max), this.momentum + amount);
        }
    }

    public Map<String, Long> activeStates() {
        return Collections.unmodifiableMap(activeStates);
    }

    public long activeUntil(final String state) {
        return activeStates.getOrDefault(state, 0L);
    }

    public void setActiveUntil(final String state, final long until) {
        if (state == null || state.isBlank() || until <= 0L) {
            activeStates.remove(state);
        } else {
            activeStates.put(state, until);
        }
    }

    public boolean stateActive(final String state, final long now) {
        return activeUntil(state) > now;
    }

    public Map<String, Long> cooldowns() {
        return Collections.unmodifiableMap(cooldowns);
    }

    public long cooldownUntil(final String id) {
        return cooldowns.getOrDefault(id, 0L);
    }

    public void setCooldownUntil(final String id, final long until) {
        if (id == null || id.isBlank() || until <= 0L) {
            cooldowns.remove(id);
        } else {
            cooldowns.put(id, until);
        }
    }

    public Map<String, Integer> discoveries() {
        return Collections.unmodifiableMap(discoveries);
    }

    public void addDiscovery(final String id, final int amount) {
        if (id != null && !id.isBlank() && amount > 0) {
            discoveries.put(id, discoveries.getOrDefault(id, 0) + amount);
        }
    }

    public Map<String, SourceWindow> sourceWindows() {
        return Collections.unmodifiableMap(sourceWindows);
    }

    public SourceWindow sourceWindow(final String sourceId, final long now) {
        return sourceWindows.computeIfAbsent(sourceId, ignored -> new SourceWindow(now, 0.0D));
    }
}
