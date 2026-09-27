package com.coremc.core.chat;

import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.RawYaml;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The full cosmetic chat pipeline, composed exactly as
 * {@code ChatFormatService#renderLine} composes it, but without Bukkit:
 *
 *   sanitise → clamp → style (solid/gradient/bold) → format → colourise
 */
class ChatPipelineTest {

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

    private static final TagCatalog TAGS = TagCatalog.parse(RawYaml.parseMap(YAML), null);
    private static final ChatStyleCatalog STYLES = ChatStyleCatalog.parse(RawYaml.parseMap(YAML), null);
    private static final Predicate<String> NO_PERMS = perm -> false;

    /** Mirror of ChatFormatService#renderLine (Bukkit parts removed). */
    private static String line(
            final PlayerProfile profile,
            final String rank,
            final String name,
            final String rawMessage,
            final boolean mayUseCodes,
            final boolean boldAllowed) {
        final String sanitised = ChatRender.sanitise(rawMessage, mayUseCodes);
        final String clamped = ChatRender.clamp(sanitised, 256);
        final ChatStyle style = CosmeticAccess.renderableStyle(profile, STYLES, NO_PERMS);
        final boolean bold = profile.chatBold() && boldAllowed;
        final String body = style == null
                ? (bold ? ChatRender.SECTION + "l" + clamped : clamped)
                : style.render(clamped, bold, 64);
        final TagDefinition tag = CosmeticAccess.renderableTag(profile, TAGS, NO_PERMS);
        return ColorUtil.colorize(ChatFormatter.format(
                ChatFormatter.DEFAULT_FORMAT, rank, tag == null ? "" : tag.display(), name, body));
    }

    private static PlayerProfile profile() {
        return PlayerProfile.createNew(UUID.randomUUID(), "Steve", 1L);
    }

    @Test
    void fullLineOrdersRankTagPlayerMessage() {
        final PlayerProfile profile = profile();
        CosmeticAccess.grantTag(profile, "grinder");
        CosmeticAccess.selectTag(profile, TAGS, "grinder", NO_PERMS);

        final String rendered = line(profile, "&c[ADMIN]", "Steve", "hello world", false, false);
        assertEquals("§c[ADMIN] §8[§cGRINDER§8] Steve§7:§r hello world", rendered);
    }

    @Test
    void noTagNoRankStillRendersCleanly() {
        final String rendered = line(profile(), "", "Steve", "hey", false, false);
        assertEquals("Steve§7:§r hey", rendered);
        assertFalse(rendered.contains("  "));
    }

    @Test
    void playersCannotInjectFormattingCodes() {
        final PlayerProfile profile = profile();
        final String rendered = line(profile, "", "Steve", "&cRED &lBOLD &kOBF", false, false);
        assertEquals("Steve§7:§r RED BOLD OBF", rendered);
        assertFalse(rendered.contains("§c"), "player colour codes are stripped");
        assertFalse(rendered.contains("§k"), "obfuscation can never be injected");
    }

    @Test
    void playersCannotInjectSectionCodesEvenWithFormatPermission() {
        final String rendered = line(profile(), "", "Steve", "§kOBF §lbold", true, false);
        assertEquals("Steve§7:§r OBF bold", rendered);
    }

    @Test
    void staffWithFormatPermissionKeepTheirCodes() {
        final String rendered = line(profile(), "", "Staff", "&aGreen text", true, false);
        assertEquals("Staff§7:§r §aGreen text", rendered);
    }

    @Test
    void solidStyleColoursOnlyTheMessageBody() {
        final PlayerProfile profile = profile();
        CosmeticAccess.grantStyle(profile, "red");
        CosmeticAccess.selectStyle(profile, STYLES, "red", NO_PERMS);
        CosmeticAccess.grantTag(profile, "grinder");
        CosmeticAccess.selectTag(profile, TAGS, "grinder", NO_PERMS);

        final String rendered = line(profile, "&2[MOD]", "Steve", "hello", false, false);
        assertEquals("§2[MOD] §8[§cGRINDER§8] Steve§7:§r §chello", rendered);
        // the rank/tag/name section is untouched by the message style
        final int bodyStart = rendered.indexOf("§r ") + 3;
        assertFalse(rendered.substring(0, bodyStart).contains("§chello"));
    }

    @Test
    void gradientStyleAppliesOnlyToTheBodyAndKeepsUnicode() {
        final PlayerProfile profile = profile();
        CosmeticAccess.grantStyle(profile, "sunset");
        CosmeticAccess.selectStyle(profile, STYLES, "sunset", NO_PERMS);

        final String message = "héllo — 你好! 😀";
        final String rendered = line(profile, "", "Steve", message, false, false);
        assertTrue(rendered.startsWith("Steve§7:§r "), "head is never gradient-coloured");
        final String body = rendered.substring("Steve§7:§r ".length());
        assertEquals(message, stripCodes(body));
    }

    @Test
    void boldRequiresPermission() {
        final PlayerProfile profile = profile();
        profile.chatBold(true);
        assertEquals("Steve§7:§r hi", line(profile, "", "Steve", "hi", false, false));
        assertEquals("Steve§7:§r §lhi", line(profile, "", "Steve", "hi", false, true));
    }

    @Test
    void overlongMessagesAreClamped() {
        final String rendered = line(profile(), "", "Steve", "a".repeat(400), false, false);
        final String body = rendered.substring("Steve§7:§r ".length());
        assertEquals(256, body.length());
    }

    private static String stripCodes(final String input) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == ChatRender.SECTION) {
                i++;
                continue;
            }
            out.append(input.charAt(i));
        }
        return out.toString();
    }
}
