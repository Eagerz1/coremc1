package com.coremc.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exact chat layout contract: {@code <RANK> <TAG> Player: Message}.
 */
class ChatFormatterTest {

    private static final String FORMAT = ChatFormatter.DEFAULT_FORMAT;

    @Test
    void rankThenTagThenPlayerThenMessage() {
        final String line = ChatFormatter.format(FORMAT, "&c[ADMIN]", "&8[&cGRINDER&8]", "Steve", "hello");
        assertEquals("&c[ADMIN] &8[&cGRINDER&8] Steve&7:&r hello", line);

        // ordering is positional, not incidental
        final int rank = line.indexOf("[ADMIN]");
        final int tag = line.indexOf("GRINDER");
        final int name = line.indexOf("Steve");
        final int message = line.indexOf("hello");
        assertTrue(rank < tag, "rank must come before the tag");
        assertTrue(tag < name, "tag must come before the player name");
        assertTrue(name < message, "player name must come before the message");
    }

    @Test
    void missingTagLeavesNoDoubleSpace() {
        final String line = ChatFormatter.format(FORMAT, "&c[ADMIN]", "", "Steve", "hi");
        assertEquals("&c[ADMIN] Steve&7:&r hi", line);
        assertFalse(headOf(line).contains("  "), "no double space in the head");
    }

    @Test
    void missingRankLeavesNoLeadingSpace() {
        final String line = ChatFormatter.format(FORMAT, "", "&8[&6OG&8]", "Alex", "hi");
        assertEquals("&8[&6OG&8] Alex&7:&r hi", line);
        assertFalse(line.startsWith(" "), "no leading space");
    }

    @Test
    void missingRankAndTagRendersPlainLine() {
        final String line = ChatFormatter.format(FORMAT, "", "", "Alex", "hi");
        assertEquals("Alex&7:&r hi", line);
        assertFalse(line.startsWith(" "));
        assertFalse(headOf(line).contains("  "));
    }

    @Test
    void nullRankAndTagBehaveLikeEmpty() {
        assertEquals("Alex&7:&r hi", ChatFormatter.format(FORMAT, null, null, "Alex", "hi"));
    }

    @Test
    void messageSpacingAndUnicodeArePreserved() {
        final String message = "  spaced   out  ✓ 你好 — done!  ";
        final String line = ChatFormatter.format(FORMAT, "", "", "Alex", message);
        assertEquals("Alex&7:&r " + message, line);
        assertTrue(line.endsWith("done!  "), "trailing message spaces survive");
    }

    @Test
    void separatorsAreConfigurable() {
        final String custom = "{rank}{tag}{player} &8» {message}";
        assertEquals("&c[ADMIN]&8[&cOG&8]Steve &8» yo",
                ChatFormatter.format(custom, "&c[ADMIN]", "&8[&cOG&8]", "Steve", "yo"));
        final String reordered = "{player} {tag}{rank}&7: {message}";
        assertEquals("Steve &8[&cOG&8]&c[ADMIN]&7: yo",
                ChatFormatter.format(reordered, "&c[ADMIN]", "&8[&cOG&8]", "Steve", "yo"));
    }

    @Test
    void formatWithoutMessageTokenStillSendsTheMessage() {
        assertEquals("Steve&7: hello", ChatFormatter.format("{player}&7:", "", "", "Steve", "hello"));
    }

    @Test
    void blankFormatFallsBackToDefault() {
        assertEquals("Steve&7:&r hi", ChatFormatter.format("  ", "", "", "Steve", "hi"));
        assertEquals("Steve&7:&r hi", ChatFormatter.format(null, "", "", "Steve", "hi"));
    }

    private static String headOf(final String line) {
        final int index = line.indexOf("&r ");
        return index < 0 ? line : line.substring(0, index);
    }
}
