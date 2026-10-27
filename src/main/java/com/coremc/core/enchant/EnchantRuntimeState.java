package com.coremc.core.enchant;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Session-only state shared by enchant activity pipelines. */
final class EnchantRuntimeState {

    private static final int STORE_SWEEP_THRESHOLD = 64;

    private final Random random = new Random();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, ComboState>> combos = new ConcurrentHashMap<>();
    private final Map<UUID, TempBoost> tempBoosts = new ConcurrentHashMap<>();

    private record ComboState(int stacks, long expiresAt) {
    }

    private record TempBoost(double xpPct, double dropsPct, long expiresAt) {
    }

    boolean roll(final double chance) {
        if (chance >= 1.0) {
            return true;
        }
        if (chance <= 0.0) {
            return false;
        }
        return random.nextDouble() < chance;
    }

    Random random() {
        return random;
    }

    boolean cooldownReady(final UUID player, final Enchant enchant) {
        if (enchant.cooldownSeconds() <= 0L) {
            return true;
        }
        final Map<String, Long> playerCooldowns = cooldowns.get(player);
        if (playerCooldowns == null) {
            return true;
        }
        final Long last = playerCooldowns.get(enchant.id());
        return last == null || System.currentTimeMillis() - last >= enchant.cooldownSeconds() * 1000L;
    }

    void markCooldown(final UUID player, final String enchantId) {
        final Map<String, Long> playerCooldowns =
                cooldowns.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>());
        playerCooldowns.put(enchantId, System.currentTimeMillis());
        if (playerCooldowns.size() > STORE_SWEEP_THRESHOLD) {
            final long now = System.currentTimeMillis();
            playerCooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 3_600_000L);
        }
    }

    int recordCombo(final UUID player, final String enchantId, final long windowSeconds,
            final int maxStacks) {
        final long now = System.currentTimeMillis();
        final Map<String, ComboState> playerCombos =
                combos.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>());
        final ComboState current = playerCombos.get(enchantId);
        final int stacks = current == null || now >= current.expiresAt()
                ? 1
                : Math.min(maxStacks, current.stacks() + 1);
        playerCombos.put(enchantId, new ComboState(stacks, now + windowSeconds * 1000L));
        if (playerCombos.size() > STORE_SWEEP_THRESHOLD) {
            playerCombos.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
        }
        return stacks;
    }

    int comboStacks(final UUID player, final String enchantId) {
        final Map<String, ComboState> playerCombos = combos.get(player);
        if (playerCombos == null) {
            return 0;
        }
        final ComboState current = playerCombos.get(enchantId);
        if (current == null || System.currentTimeMillis() >= current.expiresAt()) {
            return 0;
        }
        return current.stacks();
    }

    void setTempBoost(final UUID player, final double xpPct, final double dropsPct,
            final long durationMillis) {
        tempBoosts.put(player, new TempBoost(xpPct, dropsPct, System.currentTimeMillis() + durationMillis));
    }

    double tempXpPct(final UUID player) {
        return tempBoost(player).xpPct();
    }

    double tempDropsPct(final UUID player) {
        return tempBoost(player).dropsPct();
    }

    private TempBoost tempBoost(final UUID player) {
        final TempBoost boost = tempBoosts.get(player);
        if (boost == null || System.currentTimeMillis() >= boost.expiresAt()) {
            if (boost != null) {
                tempBoosts.remove(player);
            }
            return new TempBoost(0.0, 0.0, 0L);
        }
        return boost;
    }

    void clearPlayer(final UUID player) {
        cooldowns.remove(player);
        combos.remove(player);
        tempBoosts.remove(player);
    }
}
