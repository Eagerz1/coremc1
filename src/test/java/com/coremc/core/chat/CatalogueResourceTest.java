package com.coremc.core.chat;

import com.coremc.core.util.RawYaml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the SHIPPED tags.yml / chat.yml: 20 unique tags, 8 solid
 * colours, 5 gradients, stable ids and sane defaults.
 */
class CatalogueResourceTest {

    private static final List<String> EXPECTED_TAGS = List.of(
            "grinder", "og", "beta", "rich", "lucky", "fisher", "miner", "farmer", "slayer", "builder",
            "collector", "merchant", "riftwalker", "champion", "top", "veteran", "event", "booster",
            "creator", "legend");

    private static final List<String> EXPECTED_SOLIDS = List.of(
            "white", "gray", "red", "gold", "yellow", "green", "aqua", "light_purple");

    private static final List<String> EXPECTED_GRADIENTS = List.of(
            "sunset", "ocean", "emerald", "royal", "crimson");

    private static Map<String, Object> resource(final String name) throws IOException {
        try (InputStream stream = CatalogueResourceTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(stream, name + " must be bundled as a plugin resource");
            return RawYaml.parseMap(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static TagCatalog tags() throws IOException {
        final List<String> problems = new ArrayList<>();
        final TagCatalog catalog = TagCatalog.parse(resource("tags.yml"), problems);
        assertTrue(problems.isEmpty(), "tags.yml must parse without warnings: " + problems);
        return catalog;
    }

    private static ChatStyleCatalog styles() throws IOException {
        final List<String> problems = new ArrayList<>();
        final ChatStyleCatalog catalog = ChatStyleCatalog.parse(resource("chat.yml"), problems);
        assertTrue(problems.isEmpty(), "chat.yml must parse without warnings: " + problems);
        return catalog;
    }

    @Test
    void shipsExactlyTwentyUniqueTags() throws IOException {
        final TagCatalog catalog = tags();
        assertEquals(20, catalog.size(), "exactly 20 tags ship with CoreMC");
        assertEquals(EXPECTED_TAGS, catalog.ids(), "ids and their order are stable");
        assertEquals(20, new HashSet<>(catalog.ids()).size(), "ids are unique");
    }

    @Test
    void tagDisplaysAreCompactColouredAndUnique() throws IOException {
        final Set<String> displays = new HashSet<>();
        for (final TagDefinition tag : tags().all()) {
            assertFalse(tag.display().isBlank(), tag.id() + " needs a display");
            assertTrue(tag.display().contains("&"), tag.id() + " must be '&'-formatted");
            assertFalse(tag.display().contains("<"), tag.id() + " must not use MiniMessage");
            assertTrue(tag.display().contains(tag.id().toUpperCase(java.util.Locale.ROOT)),
                    tag.id() + " should show its uppercase label");
            assertTrue(tag.display().length() <= 32, tag.id() + " display must stay compact");
            assertTrue(displays.add(tag.display()), "duplicate display for " + tag.id());
        }
    }

    @Test
    void noTagIsOwnedByDefaultAndAllHavePermissions() throws IOException {
        for (final TagDefinition tag : tags().all()) {
            assertFalse(tag.defaultOwned(), tag.id() + " must not be granted to everyone by default");
            assertEquals("coremc.tag." + tag.id(), tag.permission());
        }
    }

    @Test
    void shipsEightSolidColours() throws IOException {
        final ChatStyleCatalog catalog = styles();
        assertEquals(8, catalog.solids().size());
        assertEquals(EXPECTED_SOLIDS, catalog.solids().stream().map(ChatStyle::id).toList());
        for (final ChatStyle style : catalog.solids()) {
            assertFalse(ChatRender.colourPrefix(style.colour()).isEmpty(), style.id() + " needs a real colour");
            assertEquals("coremc.chatcolour." + style.id(), style.permission());
        }
    }

    @Test
    void shipsFiveGradientsWithValidHexStops() throws IOException {
        final ChatStyleCatalog catalog = styles();
        assertEquals(5, catalog.gradients().size());
        assertEquals(EXPECTED_GRADIENTS, catalog.gradients().stream().map(ChatStyle::id).toList());
        for (final ChatStyle style : catalog.gradients()) {
            assertNotNull(ChatRender.normaliseHex(style.fromHex()), style.id() + " from-hex");
            assertNotNull(ChatRender.normaliseHex(style.toHex()), style.id() + " to-hex");
            final String rendered = style.render("Sample text", false, 64);
            assertTrue(rendered.contains("" + ChatRender.SECTION + "x"), style.id() + " renders legacy hex");
            assertFalse(rendered.contains("<"), style.id() + " must not use MiniMessage");
        }
    }

    @Test
    void onlyNeutralColoursAreOwnedByDefault() throws IOException {
        for (final ChatStyle style : styles().all()) {
            if (style.id().equals("white") || style.id().equals("gray")) {
                assertTrue(style.defaultOwned(), style.id() + " is a starter colour");
            } else {
                assertFalse(style.defaultOwned(), style.id() + " must be unlocked, not free");
            }
        }
    }

    @Test
    void thirteenStylesTotalWithUniqueIds() throws IOException {
        final ChatStyleCatalog catalog = styles();
        assertEquals(13, catalog.size());
        assertEquals(13, new HashSet<>(catalog.ids()).size());
    }

    @Test
    void guiSlotsFitTheConfiguredChestSizes() throws IOException {
        final Map<String, Object> tagsRoot = resource("tags.yml");
        @SuppressWarnings("unchecked")
        final Map<String, Object> gui = (Map<String, Object>) tagsRoot.get("gui");
        assertNotNull(gui);
        final int size = ((Number) gui.get("size")).intValue();
        assertEquals(0, size % 9);
        @SuppressWarnings("unchecked")
        final List<Object> slots = (List<Object>) gui.get("slots");
        assertTrue(slots.size() >= 20, "all 20 tags must fit on one page");
        for (final Object slot : slots) {
            final int value = ((Number) slot).intValue();
            assertTrue(value >= 0 && value < size, "slot " + value + " out of range");
        }
    }
}
