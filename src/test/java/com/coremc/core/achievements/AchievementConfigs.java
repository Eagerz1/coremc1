package com.coremc.core.achievements;

import org.bukkit.configuration.file.YamlConfiguration;

/** Test helper: builds a parsed {@link AchievementConfig} from a YAML string. */
public final class AchievementConfigs {

    private AchievementConfigs() {
    }

    /** Parses an achievements.yml document, throwing on any validation problem. */
    public static AchievementConfig parse(final String yaml) {
        final AchievementConfig config = new AchievementConfig(null);
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
