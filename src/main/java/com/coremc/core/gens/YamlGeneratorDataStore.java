package com.coremc.core.gens;

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
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * YAML generator store ({@code plugins/CoreMC/generators-data.yml}),
 * written atomically like the island, balance and spawner stores.
 * Corrupt files never take the system down: bad lines are skipped
 * with a warning and everything else survives.
 */
public final class YamlGeneratorDataStore implements GeneratorDataStore {

    private final Path file;
    private final Logger logger;

    public YamlGeneratorDataStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public List<GeneratorEntry> load() throws IOException {
        final List<GeneratorEntry> entries = new ArrayList<>();
        final YamlConfiguration yaml = read();
        for (final Map<?, ?> raw : yaml.getMapList("generators")) {
            try {
                final Object rawAmount = raw.get("amount");
                final Object rawOwner = raw.get("owner");
                entries.add(new GeneratorEntry(
                        String.valueOf(raw.get("world")),
                        ((Number) raw.get("x")).intValue(),
                        ((Number) raw.get("y")).intValue(),
                        ((Number) raw.get("z")).intValue(),
                        String.valueOf(raw.get("gen")),
                        UUID.fromString(String.valueOf(raw.get("island"))),
                        rawOwner == null ? null : UUID.fromString(String.valueOf(rawOwner)),
                        rawAmount instanceof Number amount ? Math.max(1, amount.intValue()) : 1));
            } catch (final RuntimeException exception) {
                logger.warning("Skipping bad generator entry in " + file + ": "
                        + exception.getMessage());
            }
        }
        return entries;
    }

    @Override
    public void save(final List<GeneratorEntry> generators) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        final List<Map<String, Object>> raw = new ArrayList<>(generators.size());
        for (final GeneratorEntry entry : generators) {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", entry.world());
            map.put("x", entry.x());
            map.put("y", entry.y());
            map.put("z", entry.z());
            map.put("gen", entry.genId());
            map.put("island", entry.islandId().toString());
            if (entry.owner() != null) {
                map.put("owner", entry.owner().toString());
            }
            map.put("amount", entry.amount());
            raw.add(map);
        }
        yaml.set("generators", raw);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private YamlConfiguration read() throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        if (!Files.isRegularFile(file)) {
            return yaml;
        }
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            logger.warning("Corrupt generator data " + file + " — starting fresh: "
                    + exception.getMessage());
        }
        return yaml;
    }
}
