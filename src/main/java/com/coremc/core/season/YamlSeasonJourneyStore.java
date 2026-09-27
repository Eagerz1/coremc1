package com.coremc.core.season;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Atomic YAML storage for Season Journey state. */
public final class YamlSeasonJourneyStore {

    private final Path file;
    private final Logger logger;

    public YamlSeasonJourneyStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public Map<UUID, SeasonJourneyProfile> loadAll() throws IOException {
        final Map<UUID, SeasonJourneyProfile> result = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return result;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (final Exception exception) {
            logger.warning("Corrupt season journey data " + file + " — starting fresh: " + exception.getMessage());
            return result;
        }
        final ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return result;
        }
        for (final String key : players.getKeys(false)) {
            try {
                final UUID id = UUID.fromString(key);
                final ConfigurationSection section = players.getConfigurationSection(key);
                if (section != null) {
                    result.put(id, readProfile(section));
                }
            } catch (final IllegalArgumentException exception) {
                logger.warning("Skipping bad season journey player '" + key + "' in " + file + ".");
            }
        }
        return result;
    }

    public void saveAll(final Map<UUID, SeasonJourneyProfile> profiles) throws IOException {
        final Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, SeasonJourneyProfile> entry : profiles.entrySet()) {
            writeProfile(yaml, "players." + entry.getKey(), entry.getValue());
        }
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        yaml.save(tmp.toFile());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException ignored) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private SeasonJourneyProfile readProfile(final ConfigurationSection section) {
        final SeasonJourneyProfile profile = new SeasonJourneyProfile();
        profile.setSeasonId(section.getString("season-id", ""));
        profile.setXp(section.getLong("xp", 0L));
        profile.setHighestLevel(section.getInt("highest-level", 1));
        profile.setCompleted(section.getBoolean("completed", false));
        profile.setCompletedAt(section.getLong("completed-at", 0L));
        for (final int level : section.getIntegerList("claimed.free")) {
            profile.claimedFree().add(level);
        }
        for (final int level : section.getIntegerList("claimed.premium")) {
            profile.claimedPremium().add(level);
        }
        profile.xpAwardKeys().addAll(section.getStringList("xp-award-keys"));
        profile.deliveredRewardKeys().addAll(section.getStringList("delivered-reward-keys"));
        final ConfigurationSection pending = section.getConfigurationSection("pending-rewards");
        if (pending != null) {
            for (final String id : pending.getKeys(false)) {
                final ConfigurationSection reward = pending.getConfigurationSection(id);
                if (reward != null) {
                    profile.pendingRewards().add(new SeasonJourneyProfile.PendingReward(
                            reward.getString("season-id", ""), reward.getString("track", "free"),
                            reward.getInt("level", 1), reward.getString("reward-key", id),
                            reward.getString("type", ""), reward.getLong("amount", 1L),
                            reward.getString("item", ""), reward.getString("display", "")));
                }
            }
        }
        final ConfigurationSection history = section.getConfigurationSection("history");
        if (history != null) {
            for (final String seasonId : history.getKeys(false)) {
                final ConfigurationSection h = history.getConfigurationSection(seasonId);
                if (h != null) {
                    profile.history().put(seasonId, new SeasonJourneyProfile.SeasonSummary(
                            seasonId, h.getString("name", seasonId), h.getLong("xp", 0L),
                            h.getInt("highest-level", 1), h.getBoolean("completed", false),
                            h.getLong("completed-at", 0L), h.getLong("archived-at", 0L)));
                }
            }
        }
        return profile;
    }

    private void writeProfile(final YamlConfiguration yaml, final String base, final SeasonJourneyProfile profile) {
        yaml.set(base + ".season-id", profile.seasonId());
        yaml.set(base + ".xp", profile.xp());
        yaml.set(base + ".highest-level", profile.highestLevel());
        yaml.set(base + ".completed", profile.completed());
        yaml.set(base + ".completed-at", profile.completedAt());
        yaml.set(base + ".claimed.free", profile.claimedFree().stream().toList());
        yaml.set(base + ".claimed.premium", profile.claimedPremium().stream().toList());
        yaml.set(base + ".xp-award-keys", profile.xpAwardKeys().stream().toList());
        yaml.set(base + ".delivered-reward-keys", profile.deliveredRewardKeys().stream().toList());
        int index = 0;
        for (final SeasonJourneyProfile.PendingReward reward : profile.pendingRewards()) {
            final String path = base + ".pending-rewards." + (++index);
            yaml.set(path + ".season-id", reward.seasonId());
            yaml.set(path + ".track", reward.track());
            yaml.set(path + ".level", reward.level());
            yaml.set(path + ".reward-key", reward.rewardKey());
            yaml.set(path + ".type", reward.type());
            yaml.set(path + ".amount", reward.amount());
            yaml.set(path + ".item", reward.item());
            yaml.set(path + ".display", reward.display());
        }
        for (final SeasonJourneyProfile.SeasonSummary summary : profile.history().values()) {
            final String path = base + ".history." + summary.seasonId();
            yaml.set(path + ".name", summary.name());
            yaml.set(path + ".xp", summary.xp());
            yaml.set(path + ".highest-level", summary.highestLevel());
            yaml.set(path + ".completed", summary.completed());
            yaml.set(path + ".completed-at", summary.completedAt());
            yaml.set(path + ".archived-at", summary.archivedAt());
        }
    }
}
