package com.coremc.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Raw SnakeYAML helper tests: dotted keys survive dump/parse, and the
 * defaults merge adds new keys without overwriting admin values or
 * exploding dotted ids into nested sections.
 */
class RawYamlTest {

    @Test
    void dottedKeysSurviveDumpAndParse() {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("miner.treasure-miner", Map.of("chance-base", 1.0, "max-level", 10));
        final String yaml = RawYaml.dump(Map.of("enchants", root));
        final Map<String, Object> parsed = RawYaml.parseMap(yaml);
        final Object enchants = parsed.get("enchants");
        assertTrue(enchants instanceof Map<?, ?>, "enchants section is a map");
        final Map<?, ?> map = (Map<?, ?>) enchants;
        assertTrue(map.containsKey("miner.treasure-miner"),
                "dotted id stays a literal key, got: " + map.keySet());
        assertFalse(map.containsKey("miner"), "no nested 'miner' section created");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergeAddsMissingKeysAndKeepsDiskValues() {
        final Map<String, Object> disk = new LinkedHashMap<>();
        disk.put("price", 99);
        disk.put("enchants", mapOf("miner.existing", mapOf("chance-base", 0.5)));

        final Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("price", 1); // must NOT overwrite the disk value
        defaults.put("new-section", mapOf("key", "value"));
        defaults.put("enchants", mapOf(
                "miner.existing", mapOf("chance-base", 0.1, "new-field", true),
                "miner.brand-new", mapOf("x", 1)));

        final List<String> added = new ArrayList<>();
        final boolean changed = RawYaml.mergeMaps(disk, defaults, "", added);

        assertTrue(changed);
        assertEquals(99, disk.get("price"), "admin values always win");
        final Map<String, Object> enchants = (Map<String, Object>) disk.get("enchants");
        final Map<?, ?> existing = (Map<?, ?>) enchants.get("miner.existing");
        assertEquals(0.5, existing.get("chance-base"), "nested admin value kept");
        assertEquals(Boolean.TRUE, existing.get("new-field"), "missing nested default added");
        assertTrue(enchants.containsKey("miner.brand-new"), "new dotted enchant added literally");
        assertFalse(enchants.containsKey("miner"), "dotted keys never split on merge");
    }

    @Test
    void mergeWithoutNewKeysReportsNoChange() {
        final Map<String, Object> disk = mapOf("a", 1, "b", List.of(1, 2));
        final Map<String, Object> defaults = mapOf("a", 99, "b", List.of(9));
        assertFalse(RawYaml.mergeMaps(disk, defaults, "", new ArrayList<>()));
        assertEquals(1, disk.get("a"));
        assertEquals(List.of(1, 2), disk.get("b"), "lists are disk-owned, never merged element-wise");
    }

    private static Map<String, Object> mapOf(final Object... kv) {
        final Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
