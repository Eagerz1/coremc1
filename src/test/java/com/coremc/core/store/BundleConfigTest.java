package com.coremc.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.crate.CrateConfig;
import com.coremc.core.lootbox.LootboxConfig;
import com.coremc.core.reward.RewardGrant;
import com.coremc.core.reward.RewardType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Bundle validation + expansion: the shipped bundles must expand to
 * EXACTLY their advertised contents, may only contain keys and
 * lootboxes, and every reference must exist.
 */
class BundleConfigTest {

    private static YamlConfiguration yaml(final String text) {
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(text);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        return parsed;
    }

    private static CrateConfig crates() throws IOException {
        final CrateConfig config = new CrateConfig(null);
        config.parse(yaml(Files.readString(Path.of("src/main/resources/crates.yml"),
                StandardCharsets.UTF_8)), Set.of("core", "monthly", "seasonal"));
        return config;
    }

    private static LootboxConfig lootboxes() throws IOException {
        final LootboxConfig config = new LootboxConfig(null);
        config.parse(yaml(Files.readString(Path.of("src/main/resources/lootboxes.yml"),
                StandardCharsets.UTF_8)), Set.of("vote", "river", "sky", "crimson", "boost"));
        return config;
    }

    private static BundleConfig bundled() throws IOException {
        final BundleConfig config = new BundleConfig(null);
        config.parse(yaml(Files.readString(Path.of("src/main/resources/bundles.yml"),
                StandardCharsets.UTF_8)), crates(), lootboxes());
        return config;
    }

    private static long count(final List<RewardGrant> grants, final RewardType type,
                              final String id) {
        return grants.stream()
                .filter(grant -> grant.type() == type && grant.id().equals(id))
                .mapToLong(RewardGrant::amount).sum();
    }

    // ------------------------------------------------------------------
    // the shipped bundles are exactly as advertised
    // ------------------------------------------------------------------

    @Test
    void starterBundleExpandsExactly() throws IOException {
        final BundleDef starter = bundled().byId("starter");
        assertEquals(500, starter.price());
        final List<RewardGrant> grants = starter.expand();
        assertEquals(2, count(grants, RewardType.KEY, "river"));
        assertEquals(1, count(grants, RewardType.KEY, "sky"));
        assertEquals(1, count(grants, RewardType.LOOTBOX, "core"));
        assertEquals(3, grants.size());
    }

    @Test
    void skyBundleExpandsExactly() throws IOException {
        final BundleDef sky = bundled().byId("sky");
        assertEquals(1000, sky.price());
        final List<RewardGrant> grants = sky.expand();
        assertEquals(3, count(grants, RewardType.KEY, "river"));
        assertEquals(2, count(grants, RewardType.KEY, "sky"));
        assertEquals(1, count(grants, RewardType.KEY, "crimson"));
        assertEquals(1, count(grants, RewardType.LOOTBOX, "core"));
        assertEquals(4, grants.size());
    }

    @Test
    void crimsonBundleExpandsExactly() throws IOException {
        final BundleDef crimson = bundled().byId("crimson");
        assertEquals(1750, crimson.price());
        final List<RewardGrant> grants = crimson.expand();
        assertEquals(3, count(grants, RewardType.KEY, "sky"));
        assertEquals(2, count(grants, RewardType.KEY, "crimson"));
        assertEquals(1, count(grants, RewardType.LOOTBOX, "core"));
        assertEquals(1, count(grants, RewardType.LOOTBOX, "monthly"));
        assertEquals(4, grants.size());
    }

    @Test
    void seasonBundleExpandsExactly() throws IOException {
        final BundleDef season = bundled().byId("season");
        assertEquals(3000, season.price());
        final List<RewardGrant> grants = season.expand();
        assertEquals(5, count(grants, RewardType.KEY, "sky"));
        assertEquals(3, count(grants, RewardType.KEY, "crimson"));
        assertEquals(2, count(grants, RewardType.LOOTBOX, "core"));
        assertEquals(1, count(grants, RewardType.LOOTBOX, "monthly"));
        assertEquals(1, count(grants, RewardType.LOOTBOX, "seasonal"));
        assertEquals(5, grants.size());
    }

    @Test
    void bundleDiscountsAreModestAndHonest() throws IOException {
        // every bundle costs less than its parts, but never less than half
        final BundleConfig bundles = bundled();
        final CrateConfig crateConfig = crates();
        final LootboxConfig lootboxConfig = lootboxes();
        for (final BundleDef bundle : bundles.all()) {
            long parts = 0;
            for (final BundleDef.Content content : bundle.contents()) {
                if (content.type() == RewardType.KEY) {
                    parts += crateConfig.key(content.id()).price() * content.amount();
                } else {
                    parts += lootboxConfig.byId(content.id()).price() * content.amount();
                }
            }
            assertTrue(bundle.price() < parts,
                    bundle.id() + " is not a discount (" + bundle.price() + " >= " + parts + ")");
            assertTrue(bundle.price() >= parts / 2,
                    bundle.id() + " discount is implausibly steep");
        }
    }

    // ------------------------------------------------------------------
    // validation is loud
    // ------------------------------------------------------------------

    private static final String VALID = """
            bundles:
              starter:
                name: "&a&lStarter Bundle"
                icon: CHEST
                price: 500
                contents:
                  - "key:river:2"
                  - "lootbox:core:1"
            """;

    @Test
    void unknownReferencesFailLoudly() throws IOException {
        final BundleConfig config = new BundleConfig(null);
        assertThrows(IllegalArgumentException.class, () -> config.parse(
                yaml(VALID.replace("key:river:2", "key:nope:2")), crates(), lootboxes()));
        assertThrows(IllegalArgumentException.class, () -> config.parse(
                yaml(VALID.replace("lootbox:core:1", "lootbox:nope:1")), crates(), lootboxes()));
    }

    @Test
    void bundlesMayOnlyContainKeysAndLootboxes() throws IOException {
        final BundleConfig config = new BundleConfig(null);
        assertThrows(IllegalArgumentException.class, () -> config.parse(
                yaml(VALID.replace("key:river:2", "item:diamond:2")), crates(), lootboxes()));
        assertThrows(IllegalArgumentException.class, () -> config.parse(
                yaml(VALID.replace("key:river:2", "money:x:500")), crates(), lootboxes()));
    }

    @Test
    void badAmountsAndShapesFailLoudly() throws IOException {
        final BundleConfig config = new BundleConfig(null);
        assertThrows(IllegalArgumentException.class, () -> config.parse(
                yaml(VALID.replace("key:river:2", "key:river:0")), crates(), lootboxes()));
        assertThrows(IllegalArgumentException.class, () -> config.parse(
                yaml(VALID.replace("key:river:2", "key:river")), crates(), lootboxes()));
        assertThrows(IllegalArgumentException.class, () -> config.parse(
                yaml(VALID.replace("price: 500", "price: -1")), crates(), lootboxes()));
        assertThrows(IllegalArgumentException.class, () -> config.parse(
                yaml("bundles: {}"), crates(), lootboxes()));
    }
}
