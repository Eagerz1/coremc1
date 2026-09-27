package com.coremc.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class IslandCoreBuffConfigTest {

    private static IslandCoreBuffConfig parse(final String yaml) {
        final IslandCoreBuffConfig config = new IslandCoreBuffConfig(null);
        final YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.loadFromString(yaml);
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(loaded);
        return config;
    }

    private static IslandCoreBuffConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/island-buffs.yml"),
                StandardCharsets.UTF_8));
    }

    @Test
    void shippedBuffsContainTheNineCoreChoices() throws IOException {
        final IslandCoreBuffConfig config = bundled();
        assertTrue(config.enabled());
        assertEquals(9, config.buffs().size());
        for (final String id : IslandCoreBuffConfig.REQUIRED_BUFFS) {
            assertNotNull(config.buff(id), id);
        }
        assertEquals(12L * 60L * 60L * 1000L, config.swapCooldownMillis());
        assertEquals(24L * 60L * 60L * 1000L, config.fortuneCooldownMillis());
    }

    @Test
    void richVeinsIsMiningCubeMechanicNotGlobalValueBuff() throws IOException {
        final IslandCoreBuffConfig.BuffDef rich = bundled().buff("rich-veins");
        assertEquals(Material.RAW_GOLD, rich.icon());
        assertEquals(7, rich.islandLevel());
        assertTrue(rich.number("proc-chance", 0) > 0);
        assertTrue(rich.number("size-max", 0) >= rich.number("size-min", 0));
        assertTrue(rich.rewards().stream().anyMatch(reward -> reward.material() == Material.ANCIENT_DEBRIS));
        assertEquals(1.0, rich.number("sell-value-multiplier", 1.0), 0.001,
                "Rich Veins must not be a global sell-value buff");
    }

    @Test
    void slayerFrenzyOnlyDeclaresSafeProgressAndOmniOutputMultipliers() throws IOException {
        final IslandCoreBuffConfig.BuffDef slayer = bundled().buff("slayer-frenzy");
        assertEquals(2.0, slayer.number("kill-progression-multiplier", 1.0), 0.001);
        assertEquals(2.0, slayer.number("omnitool-output-multiplier", 1.0), 0.001);
        assertEquals(1.0, slayer.number("vanilla-drop-multiplier", 1.0), 0.001);
        assertEquals(1.0, slayer.number("money-multiplier", 1.0), 0.001);
    }

    @Test
    void missingRequiredBuffIsRejected() throws IOException {
        final String yaml = Files.readString(Path.of("src/main/resources/island-buffs.yml"),
                StandardCharsets.UTF_8).replaceFirst("(?s)  core-surge:.*", "");
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(yaml));
        assertTrue(error.getMessage().contains("core-surge"), error.getMessage());
    }

    @Test
    void invalidClampIsRejected() throws IOException {
        final String yaml = Files.readString(Path.of("src/main/resources/island-buffs.yml"),
                StandardCharsets.UTF_8).replace("kill-progression-max: 2.0", "kill-progression-max: 0.5");
        assertThrows(IllegalArgumentException.class, () -> parse(yaml));
    }
}
