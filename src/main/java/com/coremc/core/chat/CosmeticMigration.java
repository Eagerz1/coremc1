package com.coremc.core.chat;

import com.coremc.core.player.PlayerProfile;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Safe, idempotent migration of cosmetic fields on existing profiles.
 *
 * CoreMC persists cosmetics by STABLE ID. Older/hand-edited profiles may
 * contain a display name ("&amp;8[&amp;cGRINDER&amp;8]", "GRINDER") or a
 * legacy alias ("light purple") where an id belongs. This class maps those
 * values back onto ids without ever destroying data:
 *
 *  - a value that is already a known id is left untouched,
 *  - a value that matches a tag/style display (colour codes, brackets and
 *    case ignored) is rewritten to that id,
 *  - anything else is left exactly as it is — an unknown id simply does
 *    not render (see {@link CosmeticAccess#renderableTag}), so a tag
 *    temporarily removed from config comes back when config does.
 *
 * Running it twice changes nothing the second time.
 */
public final class CosmeticMigration {

    private CosmeticMigration() {
    }

    /** Migrates tag + chat-style fields; returns true when anything changed. */
    public static boolean migrate(
            final PlayerProfile profile, final TagCatalog tags, final ChatStyleCatalog styles) {
        if (profile == null) {
            return false;
        }
        boolean changed = false;
        if (tags != null && !tags.isEmpty()) {
            changed |= migrateOwnedTags(profile, tags);
            changed |= migrateEquippedTag(profile, tags);
        }
        if (styles != null && styles.size() > 0) {
            changed |= migrateChatStyle(profile, styles);
        }
        return changed;
    }

    private static boolean migrateOwnedTags(final PlayerProfile profile, final TagCatalog tags) {
        final Set<String> owned = profile.ownedTags();
        final Set<String> rewritten = new LinkedHashSet<>();
        boolean changed = false;
        for (final String entry : owned) {
            if (tags.byId(entry).isPresent()) {
                rewritten.add(entry);
                continue;
            }
            final Optional<TagDefinition> match = matchTag(tags, entry);
            if (match.isPresent()) {
                rewritten.add(match.get().id());
                changed = true;
            } else {
                rewritten.add(entry); // unknown: keep, never lose ownership
            }
        }
        if (changed) {
            for (final String entry : owned) {
                profile.removeTag(entry);
            }
            for (final String entry : rewritten) {
                profile.addTag(entry);
            }
        }
        return changed;
    }

    private static boolean migrateEquippedTag(final PlayerProfile profile, final TagCatalog tags) {
        final String equipped = profile.equippedTag();
        if (equipped == null || CosmeticAccess.NONE.equals(equipped) || tags.byId(equipped).isPresent()) {
            return false;
        }
        final Optional<TagDefinition> match = matchTag(tags, equipped);
        if (match.isEmpty()) {
            return false;
        }
        profile.equippedTag(match.get().id());
        return true;
    }

    private static boolean migrateChatStyle(final PlayerProfile profile, final ChatStyleCatalog styles) {
        final String selected = profile.chatColor();
        if (selected == null || CosmeticAccess.NONE.equals(selected) || styles.byId(selected).isPresent()) {
            return false;
        }
        final String needle = normalise(selected);
        for (final ChatStyle style : styles.all()) {
            if (normalise(style.id()).equals(needle) || normalise(style.display()).equals(needle)) {
                profile.chatColor(style.id());
                return true;
            }
        }
        return false;
    }

    private static Optional<TagDefinition> matchTag(final TagCatalog tags, final String value) {
        final String needle = normalise(value);
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        for (final TagDefinition tag : tags.all()) {
            if (normalise(tag.id()).equals(needle)
                    || normalise(tag.display()).equals(needle)
                    || normalise(tag.nameOrDisplay()).equals(needle)) {
                return Optional.of(tag);
            }
        }
        return Optional.empty();
    }

    /** Colour codes, brackets, spaces and case removed — a comparison key. */
    static String normalise(final String value) {
        if (value == null) {
            return "";
        }
        final String stripped = ChatRender.sanitise(value, false);
        final StringBuilder out = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            final char current = stripped.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                out.append(Character.toLowerCase(current));
            }
        }
        return out.toString();
    }
}
