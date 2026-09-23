package com.coremc.core.tebex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Which gift card code each player linked, persisted in
 * {@code plugins/CoreMC/giftcards.yml} (player UUID -> Tebex gift
 * card code). Written atomically like the other stores.
 */
public final class GiftcardStore {

    private final Path file;
    private final Map<UUID, String> linked = new LinkedHashMap<>();

    public GiftcardStore(final Path file) {
        this.file = file;
    }

    /** Loads the linked cards (missing file = none). */
    public void load() throws IOException {
        linked.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IOException("corrupt giftcards file: " + exception.getMessage(), exception);
        }
        for (final String key : yaml.getKeys(false)) {
            final String code = yaml.getString(key);
            if (code == null || code.isBlank()) {
                continue;
            }
            try {
                linked.put(UUID.fromString(key), code.trim());
            } catch (final IllegalArgumentException exception) {
                // skip bad lines, keep the rest
            }
        }
    }

    /** The player's linked gift card code, or null. */
    public String codeOf(final UUID player) {
        return linked.get(player);
    }

    /** Links (or re-links) a player's gift card and writes through. */
    public void link(final UUID player, final String code) throws IOException {
        linked.put(player, code.trim());
        save();
    }

    /** Removes a player's link and writes through. */
    public void unlink(final UUID player) throws IOException {
        if (linked.remove(player) != null) {
            save();
        }
    }

    /** Persists the current links atomically. */
    public void save() throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, String> entry : linked.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
