package com.coremc.core.reward;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * YAML pending-reward store ({@code pending-rewards.yml}):
 *
 * <pre>
 * &lt;uuid&gt;:
 *   &lt;txnId&gt;:
 *     source: "lootbox:core"
 *     created: 1730000000000
 *     grants:
 *       - "money||12000|"
 *       - "key|river|1|&amp;b&amp;lRiver Key"
 * </pre>
 *
 * Written atomically (temp + move). Corrupt entries are skipped with a
 * warning, never silently corrupted or fatal.
 */
public final class YamlPendingStore implements PendingStore {

    private final Path file;
    private final Logger logger;

    public YamlPendingStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public Map<UUID, List<PendingRewards.PendingTxn>> loadAll() throws IOException {
        final Map<UUID, List<PendingRewards.PendingTxn>> pending = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return pending;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            logger.warning("Corrupt pending-rewards file " + file + " — starting fresh: "
                    + exception.getMessage());
            return pending;
        }
        for (final String uuidKey : yaml.getKeys(false)) {
            final UUID player;
            try {
                player = UUID.fromString(uuidKey);
            } catch (final IllegalArgumentException exception) {
                logger.warning("Skipping bad pending-reward owner '" + uuidKey + "'.");
                continue;
            }
            final ConfigurationSection txns = yaml.getConfigurationSection(uuidKey);
            if (txns == null) {
                continue;
            }
            final List<PendingRewards.PendingTxn> list = new ArrayList<>();
            for (final String txnId : txns.getKeys(false)) {
                final ConfigurationSection txn = txns.getConfigurationSection(txnId);
                if (txn == null) {
                    continue;
                }
                final List<RewardGrant> grants = new ArrayList<>();
                for (final String line : txn.getStringList("grants")) {
                    try {
                        grants.add(RewardGrant.parse(line));
                    } catch (final IllegalArgumentException exception) {
                        logger.warning("Skipping corrupt pending grant of " + uuidKey + ": "
                                + exception.getMessage());
                    }
                }
                if (!grants.isEmpty()) {
                    list.add(new PendingRewards.PendingTxn(txnId,
                            txn.getString("source", ""), txn.getLong("created", 0L), grants));
                }
            }
            if (!list.isEmpty()) {
                pending.put(player, list);
            }
        }
        return pending;
    }

    @Override
    public void saveAll(final Map<UUID, List<PendingRewards.PendingTxn>> pending)
            throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, List<PendingRewards.PendingTxn>> entry : pending.entrySet()) {
            for (final PendingRewards.PendingTxn txn : entry.getValue()) {
                final String base = entry.getKey() + "." + txn.txnId();
                yaml.set(base + ".source", txn.source());
                yaml.set(base + ".created", txn.created());
                final List<String> lines = new ArrayList<>(txn.grants().size());
                for (final RewardGrant grant : txn.grants()) {
                    lines.add(grant.serialize());
                }
                yaml.set(base + ".grants", lines);
            }
        }
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
