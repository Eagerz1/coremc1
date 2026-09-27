package com.coremc.core.chat;

import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.RawYaml;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mute-integration contract and async-safety of the chat pipeline.
 *
 * CoreMC hooks chat with {@code ignoreCancelled = true} at a configurable
 * priority and only ever installs a RENDERER — it never cancels and
 * re-broadcasts. A mute that cancels the event earlier therefore stops the
 * message before any cosmetic formatting happens, and no path can produce
 * a duplicate message.
 */
class ChatSafetyTest {

    private static final String YAML = """
            tags:
              grinder:
                display: "&8[&cGRINDER&8]"
            chat:
              colours:
                red:
                  display: "&cRed"
                  colour: "&c"
              gradients:
                sunset:
                  display: "&cSunset"
                  from: "#ff5555"
                  to: "#ffaa00"
            """;

    @Test
    void cancelledChatIsNeverFormatted() {
        assertTrue(ChatListener.shouldFormat(true, false), "normal chat is formatted");
        assertFalse(ChatListener.shouldFormat(true, true), "a muted (cancelled) message is skipped");
        assertFalse(ChatListener.shouldFormat(false, false), "disabled formatting leaves chat alone");
        assertFalse(ChatListener.shouldFormat(false, true));
    }

    @Test
    void renderingIsThreadSafeAndDeterministic() throws Exception {
        final TagCatalog tags = TagCatalog.parse(RawYaml.parseMap(YAML), null);
        final ChatStyleCatalog styles = ChatStyleCatalog.parse(RawYaml.parseMap(YAML), null);
        final Predicate<String> perms = perm -> false;

        final PlayerProfile profile = PlayerProfile.createNew(UUID.randomUUID(), "Steve", 1L);
        CosmeticAccess.grantTag(profile, "grinder");
        CosmeticAccess.selectTag(profile, tags, "grinder", perms);
        CosmeticAccess.grantStyle(profile, "sunset");
        CosmeticAccess.selectStyle(profile, styles, "sunset", perms);

        final ChatStyle style = CosmeticAccess.renderableStyle(profile, styles, perms);
        final TagDefinition tag = CosmeticAccess.renderableTag(profile, tags, perms);
        final String expected = ChatFormatter.format(
                ChatFormatter.DEFAULT_FORMAT, "&c[ADMIN]", tag.display(), "Steve",
                style.render("hello there", false, 64));

        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Callable<String>> work = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                work.add(() -> {
                    final ChatStyle liveStyle = CosmeticAccess.renderableStyle(profile, styles, perms);
                    final TagDefinition liveTag = CosmeticAccess.renderableTag(profile, tags, perms);
                    final String body = ChatRender.sanitise("hello there", false);
                    return ChatFormatter.format(ChatFormatter.DEFAULT_FORMAT, "&c[ADMIN]",
                            liveTag.display(), "Steve", liveStyle.render(body, false, 64));
                });
            }
            for (final Future<String> future : pool.invokeAll(work)) {
                assertEquals(expected, future.get());
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void catalogueSnapshotsAreImmutable() {
        final TagCatalog tags = TagCatalog.parse(RawYaml.parseMap(YAML), null);
        try {
            tags.ids().add("injected");
            throw new AssertionError("tag id list must be immutable");
        } catch (final UnsupportedOperationException expected) {
            // exactly what a reload-safe snapshot should do
        }
        final ChatStyleCatalog styles = ChatStyleCatalog.parse(RawYaml.parseMap(YAML), null);
        try {
            styles.solids().add(null);
            throw new AssertionError("style list must be immutable");
        } catch (final UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    void reloadSwapsCataloguesWithoutTouchingPlayerData() {
        final PlayerProfile profile = PlayerProfile.createNew(UUID.randomUUID(), "Steve", 1L);
        CosmeticAccess.grantTag(profile, "grinder");
        profile.equippedTag("grinder");

        // "reload" = a brand-new catalogue snapshot with different presentation
        final TagCatalog reloaded = TagCatalog.parse(RawYaml.parseMap("""
                tags:
                  grinder:
                    display: "&8[&9GRINDER&8]"
                    material: DIAMOND_PICKAXE
                """), null);
        assertTrue(profile.hasTag("grinder"), "ownership is untouched by a reload");
        assertEquals("&8[&9GRINDER&8]",
                CosmeticAccess.renderableTag(profile, reloaded, perm -> false).display());
    }
}
