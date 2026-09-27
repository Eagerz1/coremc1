package com.coremc.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.island.Island;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IslandCoreBuffServiceTest {

    @TempDir
    Path directory;

    private static final class ZeroRandom extends Random {
        @Override
        public double nextDouble() {
            return 0.0D;
        }

        @Override
        public int nextInt(final int bound) {
            return 0;
        }
    }

    private static Island island() {
        return new Island(UUID.randomUUID(), UUID.randomUUID(), "Owner", "world", 0,
                0, 64, 0, 100, "default", 1L,
                0.5, 65, 0.5, 0f, 0f);
    }

    private static IslandCoreBuffConfig buffConfig() throws IOException {
        final IslandCoreBuffConfig config = new IslandCoreBuffConfig(null);
        final YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.loadFromString(Files.readString(Path.of("src/main/resources/island-buffs.yml"),
                    StandardCharsets.UTF_8));
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(loaded);
        return config;
    }

    private IslandProgressionService progression() {
        final IslandProgressionService progression = new IslandProgressionService(null,
                IslandProgressionConfig.disabled(),
                new YamlIslandProgressionStore(directory.resolve("island-progression.yml"),
                        Logger.getLogger("test")),
                null, null, null, null, null, null);
        progression.load();
        return progression;
    }

    private IslandCoreBuffService service(final IslandProgressionService progression,
                                          final GameplayModifierService modifiers) throws IOException {
        final IslandCoreBuffService service = new IslandCoreBuffService(null, buffConfig(), progression,
                null, null, null, modifiers, new ZeroRandom());
        modifiers.attachCoreBuffs(service);
        return service;
    }

    @Test
    void richVeinsOnlyTriggersInsideMiningCube() throws IOException {
        final Island island = island();
        final IslandProgressionService progression = progression();
        progression.profile(island).addOwnedModule("rich-veins");
        progression.profile(island).setActiveModules(Set.of("rich-veins"));
        final GameplayModifierService modifiers = new GameplayModifierService();
        final IslandCoreBuffService service = service(progression, modifiers);
        final AtomicInteger veins = new AtomicInteger();
        service.setMiningCubes(new MiningCubeIntegration() {
            @Override
            public boolean isMiningCubeBlock(final Island testedIsland, final Location location) {
                return false;
            }

            @Override
            public void spawnRichVein(final Island island, final Location origin,
                                      final IslandCoreBuffConfig.BuffDef buff, final int size) {
                veins.incrementAndGet();
            }
        });
        assertFalse(service.tryRichVein(null, island, new Location(null, 1, 2, 3)));
        assertEquals(0, veins.get());

        service.setMiningCubes(new MiningCubeIntegration() {
            @Override
            public boolean isMiningCubeBlock(final Island testedIsland, final Location location) {
                return true;
            }

            @Override
            public void spawnRichVein(final Island island, final Location origin,
                                      final IslandCoreBuffConfig.BuffDef buff, final int size) {
                veins.incrementAndGet();
            }
        });
        assertTrue(service.tryRichVein(null, island, new Location(null, 1, 2, 3)));
        assertEquals(1, veins.get());
    }

    @Test
    void generatorOverdriveUsesIntegrationAndModifierStaysCapped() throws IOException {
        final Island island = island();
        final IslandProgressionService progression = progression();
        progression.profile(island).addOwnedModule("generator-overdrive");
        progression.profile(island).setActiveModules(Set.of("generator-overdrive"));
        final GameplayModifierService modifiers = new GameplayModifierService();
        modifiers.setClamps(buffConfig().clamps());
        final IslandCoreBuffService service = service(progression, modifiers);
        final AtomicInteger accepted = new AtomicInteger();
        service.setGenerators(new GeneratorIntegration() {
            @Override
            public int activateOverdrive(final Island testedIsland, final IslandCoreBuffConfig.BuffDef buff,
                                         final long durationMillis, final double speedMultiplier) {
                accepted.incrementAndGet();
                return 1;
            }
        });
        assertTrue(service.tryGeneratorOverdrive(island));
        assertEquals(1, accepted.get());
        assertTrue(modifiers.generatorIntervalModifier(island) < 1.0D);
    }

    @Test
    void momentumIgnoresPassiveActivityAndTriggersFromMeaningfulPlay() throws IOException {
        final Island island = island();
        final IslandProgressionService progression = progression();
        progression.profile(island).addOwnedModule("momentum");
        progression.profile(island).setActiveModules(Set.of("momentum"));
        final GameplayModifierService modifiers = new GameplayModifierService();
        final IslandCoreBuffService service = service(progression, modifiers);
        service.recordActiveGameplay(null, island, "mining", 100, true);
        assertEquals(0.0, service.momentumProgress(island), 0.001, "passive activity ignored");
        service.recordActiveGameplay(null, island, "mining", 100, false);
        assertEquals(0.0, service.momentumProgress(island), 0.001, "meter resets after triggering");
        assertTrue(service.isStateActive(island, "momentum"));
    }
}
