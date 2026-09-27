package com.coremc.core.collections;

import org.bukkit.configuration.file.YamlConfiguration;

/** Test helper: builds a parsed {@link CollectionConfig} from a YAML string. */
public final class CollectionConfigs {

    private CollectionConfigs() {
    }

    /** Parses a collections.yml document, throwing on any validation problem. */
    public static CollectionConfig parse(final String yaml) {
        final CollectionConfig config = new CollectionConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(parsed);
        return config;
    }
}
