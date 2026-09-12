package com.coremc.core.island;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure parsing rules from themes.yml — no Bukkit runtime needed. */
class ThemeServiceParseTest {

    @Test
    void decorationParsesValidEntry() {
        final Object[] parsed = ThemeService.parseDecoration("2,1,-3=OAK_SAPLING");
        assertNotNull(parsed);
        assertEquals(2, parsed[0]);
        assertEquals(1, parsed[1]);
        assertEquals(-3, parsed[2]);
        assertEquals(Material.OAK_SAPLING, parsed[3]);
    }

    @Test
    void decorationRejectsMalformedEntries() {
        assertNull(ThemeService.parseDecoration("no-equals-sign"));
        assertNull(ThemeService.parseDecoration("1,2=DIRT")); // missing a coordinate
        assertNull(ThemeService.parseDecoration("a,1,2=DIRT")); // non-numeric
        assertNull(ThemeService.parseDecoration("1,1,1=NOT_A_MATERIAL"));
        assertNull(ThemeService.parseDecoration("1,1,1=DIRT=EXTRA"));
    }

    @Test
    void chestContentParsesAndClampsCount() {
        final Object[] parsed = ThemeService.parseContent("LAVA_BUCKET:1");
        assertNotNull(parsed);
        assertEquals(Material.LAVA_BUCKET, parsed[0]);
        assertEquals(1, parsed[1]);

        final Object[] clamped = ThemeService.parseContent("BREAD:255");
        assertNotNull(clamped);
        assertEquals(64, clamped[1]); // clamped to a stack
    }

    @Test
    void chestContentRejectsMalformedEntries() {
        assertNull(ThemeService.parseContent("BREAD")); // missing count
        assertNull(ThemeService.parseContent("BREAD:many"));
        assertNull(ThemeService.parseContent("NO_SUCH_ITEM:2"));
    }
}
