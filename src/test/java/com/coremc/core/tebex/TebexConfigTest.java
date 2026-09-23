package com.coremc.core.tebex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Tebex settings: off until the owner fills in the Plugin API key. */
final class TebexConfigTest {

    private static TebexConfig parse(final String yamlText) {
        final TebexConfig config = new TebexConfig(null);
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(yamlText);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(yaml);
        return config;
    }

    @Test
    void disabledByDefaultAndWithoutAKey() {
        assertFalse(parse("api-base: \"https://plugin.tebex.io\"").enabled());
        assertFalse(parse("enabled: true").enabled(), "enabled without a key stays off");
        assertTrue(parse("""
                enabled: true
                secret-key: "test-secret"
                """).enabled());
    }

    @Test
    void apiBaseDefaultsAndTrimsTrailingSlash() {
        assertEquals("https://plugin.tebex.io", parse("enabled: false").apiBase());
        assertEquals("http://127.0.0.1:8123",
                parse("api-base: \"http://127.0.0.1:8123/\"").apiBase());
        assertEquals("https://plugin.tebex.io", parse("api-base: \"\"").apiBase());
    }

    @Test
    void secretKeyIsTrimmed() {
        assertEquals("abc123", parse("secret-key: \"  abc123  \"").secretKey());
    }
}
