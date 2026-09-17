package com.coremc.core.shop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * YAML economy store: {@code plugins/CoreMC/balances.yml} mapping
 * player UUIDs to coin balances, written atomically (temp file +
 * move) like the island store.
 */
public final class YamlEconomyStore implements EconomyStore {

    private final Path file;
    private final Logger logger;

    public YamlEconomyStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public Map<UUID, Double> loadAll() throws IOException {
        final Map<UUID, Double> balances = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return balances;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            logger.warning("Corrupt balances file " + file + " — starting fresh: " + exception.getMessage());
            return balances;
        }
        for (final String key : yaml.getKeys(false)) {
            try {
                balances.put(UUID.fromString(key), Money.round(yaml.getDouble(key)));
            } catch (final IllegalArgumentException exception) {
                logger.warning("Skipping bad balance entry '" + key + "' in " + file + ".");
            }
        }
        return balances;
    }

    @Override
    public void saveAll(final Map<UUID, Double> balances) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, Double> entry : balances.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
