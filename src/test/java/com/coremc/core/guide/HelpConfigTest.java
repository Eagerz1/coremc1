package com.coremc.core.guide;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class HelpConfigTest {

    @Test
    void bundledGuideLoadsRequiredCategoriesAndRegisteredCommandMetadata() throws Exception {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File("src/main/resources/help.yml"));
        final HelpConfig config = new HelpConfig(null);
        config.parse(yaml);

        assertTrue(config.enabled());
        assertNotNull(config.category("getting-started"));
        assertNotNull(config.category("quests"));
        assertNotNull(config.category("commands"));
        assertTrue(config.commands().stream().anyMatch(entry -> entry.display().equals("/quests")));
        assertTrue(config.commands().stream().anyMatch(entry -> entry.display().equals("/help")));
        assertTrue(config.commands().stream().anyMatch(entry -> entry.pluginCommand().equals("gens")));
    }

    @Test
    void invalidGuideFailsClearly() throws Exception {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("categories: {}\ncommands: {}\n");
        final HelpConfig config = new HelpConfig(null);
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> config.parse(yaml));
        assertTrue(thrown.getMessage().contains("help.yml"));
        assertTrue(thrown.getMessage().contains("getting-started"));
    }
}
