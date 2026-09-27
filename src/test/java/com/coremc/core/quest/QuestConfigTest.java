package com.coremc.core.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class QuestConfigTest {

    @Test
    void bundledConfigLoadsRequiredPoolsAndIntegrationLocks() throws Exception {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File("src/main/resources/quests.yml"));
        final QuestConfig config = new QuestConfig(null);
        config.parse(yaml);

        assertTrue(config.enabled());
        assertTrue(config.dailyCount() >= 3 && config.dailyCount() <= 5);
        assertFalse(config.templates(QuestConfig.Scope.DAILY).isEmpty());
        assertFalse(config.templates(QuestConfig.Scope.WEEKLY).isEmpty());
        assertFalse(config.templates(QuestConfig.Scope.ISLAND).isEmpty());
        assertTrue(config.template("daily-mining-cube").requirements().systems().contains("mining-cube"));
        assertEquals(0, config.template("seasonal-placeholder").weight());
        assertEquals(0, config.template("contract-placeholder").weight());
    }

    @Test
    void invalidObjectiveIsRejectedClearly() {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString("""
                    settings:
                      daily: {count: 3, reset-hours: 24}
                      weekly: {count: 3, reset-hours: 168}
                      island-challenges: {count: 1, reset-hours: 168}
                    templates:
                      bad:
                        type: daily
                        rewards: {tokens: {type: sky-tokens, amount: 1}}
                        objective: {action: crop-harvested, target: 0}
                      weekly:
                        type: weekly
                        rewards: {tokens: {type: sky-tokens, amount: 1}}
                        objective: {action: daily-completed, target: 1}
                      island:
                        type: island
                        rewards: {tokens: {type: sky-tokens, amount: 1}}
                        objective: {action: crop-harvested, target: 1}
                    """);
        } catch (final Exception exception) {
            fail(exception);
        }
        final QuestConfig config = new QuestConfig(null);
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> config.parse(yaml));
        assertTrue(thrown.getMessage().contains("target must be positive"));
        assertTrue(thrown.getMessage().contains("quests.yml"));
    }
}
