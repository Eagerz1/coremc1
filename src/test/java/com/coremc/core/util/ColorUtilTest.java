package com.coremc.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorUtilTest {

    @Test
    void translatesAmpersandCodes() {
        assertEquals("§b§lCOREMC §8» §fHello", ColorUtil.colorize("&b&lCOREMC &8» &fHello"));
    }

    @Test
    void nullAndEmptyBecomeEmpty() {
        assertEquals("", ColorUtil.colorize(null));
        assertEquals("", ColorUtil.colorize(""));
    }

    @Test
    void plainTextPassesThrough() {
        assertEquals("plain", ColorUtil.colorize("plain"));
    }
}
