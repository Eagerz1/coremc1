package com.coremc.core.rank;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Rank persistence ({@code plugins/CoreMC/ranks-data.yml}): the current
 * season number plus every ranked player's rank, River keys and the
 * last season they were paid out in. Written atomically after each
 * change, like every other CoreMC store.
 */
public final class YamlRankStore {

    /** A player's rank state (mutable — the service updates it in place). */
    public static final class PlayerRank {
        public String name;
        public String rankId;
        public int riverKeys;
        public int lastPayout;

        public PlayerRank(final String name, final String rankId, final int riverKeys,
                          final int lastPayout) {
            this.name = name;
            this.rankId = rankId;
            this.riverKeys = riverKeys;
            this.lastPayout = lastPayout;
        }
    }

    private final Path file;
    private final Logger logger;

    public YamlRankStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Loads the season and all player ranks; corrupt data starts fresh with a warning. */
    public StoreData load() throws IOException {
        final StoreData data = new StoreData();
        if (!Files.isRegularFile(file)) {
            return data;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            logger.warning("Corrupt rank data " + file + " — starting fresh: "
                    + exception.getMessage());
            return data;
        }
        data.season = Math.max(1, yaml.getInt("season", 1));
        final ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return data;
        }
        for (final String key : players.getKeys(false)) {
            try {
                final UUID playerId = UUID.fromString(key);
                final ConfigurationSection section = players.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                data.players.put(playerId, new PlayerRank(
                        section.getString("name", "player"),
                        section.getString("rank", ""),
                        Math.max(0, section.getInt("river-keys", 0)),
                        Math.max(0, section.getInt("last-payout", 0))));
            } catch (final IllegalArgumentException exception) {
                logger.warning("Skipping bad rank entry '" + key + "' in " + file + ".");
            }
        }
        return data;
    }

    /** Persists the season and all player ranks atomically. */
    public void save(final int season, final Map<UUID, PlayerRank> players) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("season", season);
        for (final Map.Entry<UUID, PlayerRank> entry : players.entrySet()) {
            final PlayerRank rank = entry.getValue();
            yaml.set("players." + entry.getKey() + ".name", rank.name);
            yaml.set("players." + entry.getKey() + ".rank", rank.rankId);
            yaml.set("players." + entry.getKey() + ".river-keys", rank.riverKeys);
            yaml.set("players." + entry.getKey() + ".last-payout", rank.lastPayout);
        }
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** What {@link #load} returns: season + player table. */
    public static final class StoreData {
        public int season = 1;
        public final Map<UUID, PlayerRank> players = new LinkedHashMap<>();
    }
}
