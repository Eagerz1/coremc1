package com.coremc.core.essence;

import com.coremc.core.spawner.SpawnerVariant;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and validates {@code essences.yml}: how the three virtual
 * essences are earned. Like the shop catalogue, a broken file fails
 * loudly at load time with every problem collected into one
 * exception.
 *
 * <pre>
 * slayer:
 *   per-mob:            # essence per killed mob, by spawner variant
 *     normal: 1
 *     advanced: 2
 *     ancient: 3
 *     mythic: 5
 * mining:
 *   blocks:             # material -&gt; essence per natural block broken
 *     COAL_ORE: 1
 * farming:
 *   blocks:             # material -&gt; essence per fully grown harvest
 *     WHEAT: 1
 * </pre>
 */
public final class EssenceConfig {

    private static final String FILE_NAME = "essences.yml";

    private final JavaPlugin plugin;
    private final Map<SpawnerVariant, Long> slayerPerMob = new LinkedHashMap<>();
    private final Map<Material, Long> miningBlocks = new LinkedHashMap<>();
    private final Map<Material, Long> farmingBlocks = new LinkedHashMap<>();

    public EssenceConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Extracts the default essences.yml on first run, then parses and validates it. */
    public void load() {
        final File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (final Exception exception) {
            throw new IllegalArgumentException("cannot read " + FILE_NAME + ": " + exception.getMessage(),
                    exception);
        }
        parse(yaml);
    }

    /** Parses and validates a YAML document (also used by tests). */
    void parse(final YamlConfiguration yaml) {
        final List<String> problems = new ArrayList<>();

        final ConfigurationSection slayer = yaml.getConfigurationSection("slayer.per-mob");
        if (slayer == null) {
            problems.add("missing 'slayer.per-mob' mapping");
        } else {
            for (final SpawnerVariant variant : SpawnerVariant.values()) {
                final long amount = slayer.getLong(variant.name().toLowerCase(), -1);
                if (amount < 0) {
                    problems.add("slayer.per-mob: missing amount for "
                            + variant.name().toLowerCase());
                } else {
                    slayerPerMob.put(variant, amount);
                }
            }
        }

        parseBlocks(yaml.getConfigurationSection("mining.blocks"), "mining.blocks", miningBlocks, problems);
        parseBlocks(yaml.getConfigurationSection("farming.blocks"), "farming.blocks", farmingBlocks, problems);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("broken essences.yml: " + String.join("; ", problems));
        }
    }

    private void parseBlocks(final ConfigurationSection root, final String where,
                             final Map<Material, Long> into, final List<String> problems) {
        if (root == null) {
            return; // an empty earning map is fine — that essence is simply not earned
        }
        for (final String key : root.getKeys(false)) {
            final Material material = Material.matchMaterial(key);
            if (material == null) {
                problems.add(where + ": unknown material '" + key + "'");
                continue;
            }
            final long amount = root.getLong(key, -1);
            if (amount < 0) {
                problems.add(where + ": bad amount for '" + key + "'");
                continue;
            }
            into.put(material, amount);
        }
    }

    /** Slayer Essence per killed mob of the given variant. */
    public long slayerPerMob(final SpawnerVariant variant) {
        return slayerPerMob.getOrDefault(variant, 1L);
    }

    /** Immutable mining reward map (material -> essence per natural block). */
    public Map<Material, Long> miningBlocks() {
        return Collections.unmodifiableMap(miningBlocks);
    }

    /** Immutable farming reward map (material -> essence per harvest). */
    public Map<Material, Long> farmingBlocks() {
        return Collections.unmodifiableMap(farmingBlocks);
    }

    /** True when mining this material can pay essence (so placements are tracked). */
    public boolean miningEligible(final Material material) {
        return miningBlocks.containsKey(material);
    }
}
