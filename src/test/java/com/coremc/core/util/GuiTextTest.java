package com.coremc.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The CoreMC GUI design language: grouped money, compact durations,
 * roman tier numerals and — most importantly — the live ✔ / ✖
 * affordability markers every price and requirement carries.
 */
class GuiTextTest {

    @Test
    void moneyIsGroupedAndFreeOfNoise() {
        assertEquals("$25,000", GuiText.money(25000));
        assertEquals("$1,234.5", GuiText.money(1234.5));
        assertEquals("$0", GuiText.money(0));
        assertEquals("$6,000,000", GuiText.money(6_000_000));
    }

    @Test
    void numbersAreGroupedToo() {
        assertEquals("2,500", GuiText.number(2500));
        assertEquals("0.2", GuiText.number(0.2));
    }

    @Test
    void affordablePricesShowAGreenTick() {
        assertEquals("&7ᴘʀɪᴄᴇ: &a$25,000 &a✔",
                GuiText.cost("price", GuiText.money(25000), true));
    }

    @Test
    void unaffordablePricesShowARedCross() {
        assertEquals("&7ᴘʀɪᴄᴇ: &c$25,000 &c✖",
                GuiText.cost("price", GuiText.money(25000), false));
    }

    @Test
    void upgradeCostsUseTheSameIndicator() {
        assertEquals("&7ᴜᴘɢʀᴀᴅᴇ ᴄᴏsᴛ: &a$50,000 &a✔",
                GuiText.cost("upgrade cost", GuiText.money(50000), true));
        assertEquals("&7ᴜᴘɢʀᴀᴅᴇ ᴄᴏsᴛ: &c$50,000 &c✖",
                GuiText.cost("upgrade cost", GuiText.money(50000), false));
    }

    @Test
    void requirementsUseTheSameIndicator() {
        assertEquals("&7ɪsʟᴀɴᴅ ᴘᴏɪɴᴛs: &a2,500 &a✔",
                GuiText.requirement("island points", "2,500", true));
        assertEquals("&7ɪsʟᴀɴᴅ ᴘᴏɪɴᴛs: &c2,500 &c✖",
                GuiText.requirement("island points", "2,500", false));
    }

    @Test
    void labelledValuesAreGreyWithAColouredValue() {
        assertEquals("&7ᴛɪᴇʀ: &fɪɪɪ", GuiText.line("tier", "&f", "III"));
        assertEquals("&7ɪɴᴛᴇʀᴠᴀʟ: &f30s", GuiText.value("interval", "&f", "30s"));
    }

    @Test
    void clickAndHintLinesAreConsistent() {
        assertEquals("&eᴄʟɪᴄᴋ ᴛᴏ ᴘᴜʀᴄʜᴀsᴇ", GuiText.click("Click to purchase"));
        assertEquals("&8sʜɪғᴛ-ᴄʟɪᴄᴋ ғᴏʀ 8", GuiText.hint("Shift-click for 8"));
        assertTrue(GuiText.blank().isEmpty());
    }

    @Test
    void durationsAreCompact() {
        assertEquals("30s", GuiText.seconds(30));
        assertEquals("2m", GuiText.seconds(120));
        assertEquals("2m 30s", GuiText.seconds(150));
        assertEquals("1h 5m", GuiText.seconds(3900));
        assertEquals("0s", GuiText.seconds(-5));
    }

    @Test
    void tiersRenderAsRomanNumerals() {
        assertEquals("I", GuiText.roman(1));
        assertEquals("III", GuiText.roman(3));
        assertEquals("IX", GuiText.roman(9));
        assertEquals("X", GuiText.roman(10));
        assertEquals("41", GuiText.roman(41));
    }

    @Test
    void progressReadsAsAFraction() {
        assertEquals("8/20", GuiText.progress(8, 20));
    }
}
