package com.coremc.core.quest;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

/** Daily objective assignment, event progress and atomic reward claiming. */
public final class QuestService implements Listener {

    private final CoreMCPlugin plugin;
    private final Map<String, QuestDefinition> definitions = new LinkedHashMap<>();

    public QuestService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    public int load() {
        definitions.clear();
        final var section = plugin.getConfig().getConfigurationSection("daily-quests");
        if (section == null) {
            return 0;
        }
        for (final String rawId : section.getKeys(false)) {
            final var row = section.getConfigurationSection(rawId);
            if (row == null) {
                continue;
            }
            final Material icon = Material.matchMaterial(row.getString("icon", "PAPER"));
            final QuestDefinition.Metric metric;
            try {
                metric = QuestDefinition.Metric.valueOf(
                        row.getString("metric", "MINE_BLOCK").toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException exception) {
                plugin.getLogger().warning("Daily quest '" + rawId + "' has an invalid metric; skipped.");
                continue;
            }
            final long target = row.getLong("target", 0L);
            if (icon == null || target <= 0L) {
                plugin.getLogger().warning("Daily quest '" + rawId + "' has an invalid icon/target; skipped.");
                continue;
            }
            final String id = rawId.toLowerCase(Locale.ROOT);
            definitions.put(id, new QuestDefinition(
                    id, row.getString("display", "&f" + id), icon, metric, target,
                    Math.max(0L, row.getLong("reward-credits", 0L)),
                    Math.max(0L, row.getLong("reward-sky-tokens", 0L))));
        }
        return definitions.size();
    }

    public List<QuestDefinition> all() {
        return new ArrayList<>(definitions.values());
    }

    public Optional<QuestDefinition> definition(final String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public void ensureToday(final PlayerProfile profile) {
        final String today = dayKey();
        if (today.equals(profile.questDay()) && !profile.dailyQuests().isEmpty()) {
            return;
        }
        profile.questDay(today);
        profile.dailyQuests(QuestRotation.select(new ArrayList<>(definitions.keySet()),
                profile.uuid(), today, 3));
        profile.clearQuestProgress();
        for (final String id : profile.dailyQuests()) {
            write(profile, id, new QuestRotation.Progress(0L, false, false));
        }
        plugin.playerData().persistImportant(profile);
    }

    public QuestRotation.Progress progress(final PlayerProfile profile, final String id) {
        final Map<String, Object> row = profile.questProgressOf(id);
        return new QuestRotation.Progress(
                number(row.get("progress")), Boolean.TRUE.equals(row.get("done")),
                Boolean.TRUE.equals(row.get("claimed")));
    }

    public void record(final Player player, final QuestDefinition.Metric metric, final long amount) {
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        ensureToday(profile);
        boolean changed = false;
        for (final String id : profile.dailyQuests()) {
            final QuestDefinition quest = definitions.get(id);
            if (quest == null || quest.metric() != metric) {
                continue;
            }
            final QuestRotation.Progress before = progress(profile, id);
            final QuestRotation.Progress after = QuestRotation.advance(before, amount, quest.target());
            if (!after.equals(before)) {
                write(profile, id, after);
                changed = true;
                if (!before.done() && after.done()) {
                    plugin.messages().sendPrefixed(player, "quest.completed", Map.of("quest", quest.display()));
                }
            }
        }
        if (changed) {
            plugin.playerData().markDirty(profile.uuid());
        }
    }

    public boolean claim(final Player player, final PlayerProfile profile, final QuestDefinition quest) {
        ensureToday(profile);
        final QuestRotation.Progress current = progress(profile, quest.id());
        if (!current.done() || current.claimed()) {
            return false;
        }
        if ((quest.rewardCredits() > 0L
                && !plugin.economy().fitsDeposit(profile, Currency.CREDITS, quest.rewardCredits()))
                || (quest.rewardTokens() > 0L
                && !plugin.economy().fitsDeposit(profile, Currency.SKY_TOKENS, quest.rewardTokens()))) {
            plugin.messages().sendPrefixed(player, "quest.overflow", Map.of());
            return false;
        }
        if (quest.rewardCredits() > 0L) {
            plugin.economy().deposit(profile, Currency.CREDITS, quest.rewardCredits());
        }
        if (quest.rewardTokens() > 0L) {
            plugin.economy().deposit(profile, Currency.SKY_TOKENS, quest.rewardTokens());
        }
        write(profile, quest.id(), new QuestRotation.Progress(current.amount(), true, true));
        plugin.islands().islandOf(player.getUniqueId()).ifPresent(island -> {
            island.addStat("missions-completed", 1L);
            plugin.islands().markDirty(island);
        });
        plugin.playerData().persistImportant(profile);
        plugin.messages().sendPrefixed(player, "quest.claimed", Map.of(
                "credits", String.valueOf(quest.rewardCredits()),
                "tokens", String.valueOf(quest.rewardTokens())));
        return true;
    }

    private void write(final PlayerProfile profile, final String id, final QuestRotation.Progress progress) {
        profile.setQuestProgress(id, new LinkedHashMap<>(Map.of(
                "progress", progress.amount(), "done", progress.done(), "claimed", progress.claimed())));
    }

    private static long number(final Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static String dayKey() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final Material material = event.getBlock().getType();
        final String name = material.name();
        if (name.endsWith("_LOG") || name.endsWith("_STEM") || name.endsWith("_HYPHAE")) {
            record(event.getPlayer(), QuestDefinition.Metric.CHOP_LOG, 1L);
        } else if (isCrop(material)) {
            if (isHarvestable(event.getBlock())) {
                record(event.getPlayer(), QuestDefinition.Metric.HARVEST_CROP, 1L);
            }
        } else {
            record(event.getPlayer(), QuestDefinition.Metric.MINE_BLOCK, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            record(event.getPlayer(), QuestDefinition.Metric.CATCH_FISH, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(final EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();
        if (killer != null) {
            record(killer, QuestDefinition.Metric.KILL_MOB, 1L);
        }
    }

    private static boolean isCrop(final Material material) {
        return switch (material) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART,
                    COCOA, SUGAR_CANE, CACTUS, MELON, PUMPKIN -> true;
            default -> false;
        };
    }

    /** Prevents repeatedly breaking immature replants for mission credit. */
    private static boolean isHarvestable(final Block block) {
        if (block.getBlockData() instanceof Ageable crop) {
            return crop.getAge() >= crop.getMaximumAge();
        }
        return switch (block.getType()) {
            case MELON, PUMPKIN -> true;
            case SUGAR_CANE, CACTUS -> block.getRelative(0, -1, 0).getType() == block.getType();
            default -> false;
        };
    }
}
