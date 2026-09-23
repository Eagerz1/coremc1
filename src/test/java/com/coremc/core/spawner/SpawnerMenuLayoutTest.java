package com.coremc.core.spawner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Column-major slot maths for the spawner menu: mobs flow down three
 * columns (5 rows), the first mob stays at slot 10.
 */
final class SpawnerMenuLayoutTest {

    @Test
    void mobsFlowDownThreeColumns() {
        // column 1
        assertEquals(10, SpawnerMenuLayout.mobSlot(0));
        assertEquals(19, SpawnerMenuLayout.mobSlot(1));
        assertEquals(28, SpawnerMenuLayout.mobSlot(2));
        assertEquals(37, SpawnerMenuLayout.mobSlot(3));
        assertEquals(46, SpawnerMenuLayout.mobSlot(4));
        // column 2
        assertEquals(11, SpawnerMenuLayout.mobSlot(5));
        assertEquals(20, SpawnerMenuLayout.mobSlot(6));
        assertEquals(29, SpawnerMenuLayout.mobSlot(7));
        assertEquals(38, SpawnerMenuLayout.mobSlot(8));
        assertEquals(47, SpawnerMenuLayout.mobSlot(9));
        // column 3
        assertEquals(12, SpawnerMenuLayout.mobSlot(10));
        assertEquals(21, SpawnerMenuLayout.mobSlot(11));
        assertEquals(30, SpawnerMenuLayout.mobSlot(12));
        assertEquals(39, SpawnerMenuLayout.mobSlot(13));
        assertEquals(48, SpawnerMenuLayout.mobSlot(14));
    }

    @Test
    void mobIndexAtDecodesEveryMobSlot() {
        for (int flatIndex = 0; flatIndex < SpawnerMenuLayout.MAX_MOBS; flatIndex++) {
            assertEquals(flatIndex,
                    SpawnerMenuLayout.mobIndexAt(SpawnerMenuLayout.mobSlot(flatIndex)),
                    "flat index " + flatIndex);
        }
    }

    @Test
    void nonMobSlotsDecodeToMinusOne() {
        assertEquals(-1, SpawnerMenuLayout.mobIndexAt(SpawnerMenuLayout.GUIDE));
        assertEquals(-1, SpawnerMenuLayout.mobIndexAt(SpawnerMenuLayout.LUCK));
        assertEquals(-1, SpawnerMenuLayout.mobIndexAt(SpawnerMenuLayout.CLOSE));
        assertEquals(-1, SpawnerMenuLayout.mobIndexAt(13), "filler between mob columns");
        assertEquals(-1, SpawnerMenuLayout.mobIndexAt(18), "row start left of the first mob column");
        assertEquals(-1, SpawnerMenuLayout.mobIndexAt(9), "left edge column");
        assertEquals(-1, SpawnerMenuLayout.mobIndexAt(53), "bottom right corner");
        assertEquals(-1, SpawnerMenuLayout.mobIndexAt(-1));
        assertEquals(-1, SpawnerMenuLayout.mobIndexAt(54));
    }

    @Test
    void mobLookupFlattensGroupsInOrder(@TempDir final Path tempDir) throws Exception {
        final Path file = tempDir.resolve("spawners.yml");
        Files.writeString(file, """
                variants:
                  normal: {rate: 1.0, count: 2, nearby-limit: 8, auto-kill: false}
                  advanced: {rate: 2.0, count: 3, nearby-limit: 12, auto-kill: false}
                  ancient: {rate: 3.0, count: 4, nearby-limit: 16, auto-kill: false}
                  mythic: {rate: 4.0, count: 6, nearby-limit: 24, auto-kill: true}
                groups:
                  alpha:
                    name: "Alpha"
                    essence: {item: PRISMARINE_SHARD, name: "A Essence"}
                    relic: {item: HEART_OF_THE_SEA, name: "A Relic"}
                    mobs:
                      pig: {entity: PIG, name: "Pig", drop: {item: BONE, name: "Tusk"},
                            spawner-cost: 100, unlock: {},
                            upgrades: {advanced: {essence: 1}, ancient: {essence: 2}, mythic: {essence: 3}}}
                      cow: {entity: COW, name: "Cow", drop: {item: BONE, name: "Bell"},
                            spawner-cost: 100, unlock: {},
                            upgrades: {advanced: {essence: 1}, ancient: {essence: 2}, mythic: {essence: 3}}}
                  beta:
                    name: "Beta"
                    essence: {item: ECHO_SHARD, name: "B Essence"}
                    relic: {item: NETHER_STAR, name: "B Relic"}
                    mobs:
                      zombie: {entity: ZOMBIE, name: "Zombie", drop: {item: BONE, name: "Brain"},
                               spawner-cost: 100, unlock: {},
                               upgrades: {advanced: {essence: 1}, ancient: {essence: 2}, mythic: {essence: 3}}}
                """);
        final SpawnerConfig config = new SpawnerConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(parsed);
        assertEquals(EntityType.PIG, SpawnerMenuLayout.mob(config, 0).entity());
        assertEquals(EntityType.COW, SpawnerMenuLayout.mob(config, 1).entity());
        assertEquals(EntityType.ZOMBIE, SpawnerMenuLayout.mob(config, 2).entity());
        assertNull(SpawnerMenuLayout.mob(config, 3));
        assertNull(SpawnerMenuLayout.mob(config, -1));
    }
}
